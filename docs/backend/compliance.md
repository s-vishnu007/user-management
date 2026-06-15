# `com.example.cp.compliance` — GDPR / CCPA data-subject rights

> Package: `control-panel-api/src/main/java/com/example/cp/compliance`
> Migration: `control-panel-api/src/main/resources/db/changelog/changes/15-compliance.sql`
> Related DDL (not in this package, but central to it): `08-audit.sql`, `18-webhook-fanout-integrity.sql` (the `audit_log` immutability trigger and its GDPR-redaction exception).

## Module overview

This package implements the control panel's **data-subject rights** under GDPR / CCPA: the **right of access / data portability** (export a subject's personal data as machine-readable JSON), the **right to erasure** (GDPR Art. 17, "right to be forgotten"), and **tenant off-boarding** (erase every member's PII when an organization is deleted). It is a thin, security-sensitive vertical: one REST controller (`DataPrivacyController`) sitting on two services — `DataExportService` (read-only assembly of an export document) and `ErasureService` (the transactional pseudonymise-and-scrub operation) — plus a tiny durable ledger (`ErasureLog` + `ErasureLogRepository`) that records *that* a DSAR happened without ever storing the personal data itself.

The governing design principle is **pseudonymise, don't destroy the trail**. Erasure removes directly-identifying PII (email, name, password hash, SSO/MFA enrollment) and bumps the user's status to `DELETED`, but it deliberately *retains* the append-only `audit_log` rows for the compliance/security record. The PII embedded in those retained rows (raw client IP, plus any email a writer dropped into `payload_json`) is scrubbed in place, within the same transaction, through a *narrowly* widened exception to the `audit_log` immutability trigger. Exports are built by construction never to include secret material (no password hashes, API-key hashes, SSO client secrets, or TOTP secrets).

### How it fits the bigger picture

```
                 HTTP (admin UI / DPO tooling)
                          │
                 DataPrivacyController          ← in-method authz, audit-fail-closed
                 ┌────────┴────────┐
        DataExportService     ErasureService
        (read-only JSON)      (@Transactional mutate)
            │  reads               │  mutates + scrubs
   users / orgs / members /   users (pseudonymise) · sso_identities (delete)
   subscriptions / apikeys /  user_mfa (delete) · SessionRevocationStore (tv bump)
   sso_* / audit_log          audit_log (redact PII via GUC-gated UPDATE)
                                   │  writes
                              ErasureLog (PII-free ledger)
```

The compliance package leans heavily on already-existing repositories from sibling buckets (`users`, `orgs`, `subscriptions`, `apikeys`, `audit`, `mfa`, `auth`) rather than owning that data — it is an orchestrator over the rest of the domain. Its two most important external collaborators are the **audit subsystem** (`AuditWriter`, `AuditContext`, `AuditInterceptor`, `AuditLogRepository`) and the **session-revocation subsystem** (`SessionRevocationStore`), and its single most subtle piece of machinery is the database-level interplay with the `audit_log` immutability trigger.

---

## File-by-file

### `ErasureLog.java`

**Responsibility.** JPA entity for the `erasure_log` table: a deliberately small, **PII-free ledger** with one row per data-subject request. It records *what* subject was acted on, *by whom*, and *when* — never the personal data. This is the artefact a DPO/auditor consults to *evidence* that DSARs were honoured. It complements (does not replace) the `audit_log` rows the controller emits.

**Mapping.** `@Entity @Table(name = "erasure_log")`, Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`. Maps to `15-compliance.sql`.

**Fields / columns:**

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | Assigned by the service via `Ids.newId()` — not DB-generated. |
| `String subjectType` | `subject_type` `VARCHAR(16) NOT NULL` | DB `CHECK (subject_type IN ('user','org'))`. Stored **lowercase**. |
| `UUID subjectId` | `subject_id NOT NULL` | The user id or org id acted on. |
| `UUID requestedBy` | `requested_by` (nullable) | The actor who initiated it (super-admin / subject / org admin). |
| `OffsetDateTime requestedAt` | `requested_at NOT NULL` | Service sets `now()`. |
| `OffsetDateTime completedAt` | `completed_at` (nullable) | Set when the op *finishes*; a started-but-failed request stays distinguishable. |
| `String action` | `action` `VARCHAR(16) NOT NULL` | DB `CHECK (action IN ('export','erase'))`. Stored **lowercase**. |

**Nested enums** — both exist to keep call sites type-safe while persisting the lowercase string the DB CHECK expects:

- `SubjectType { USER("user"), ORG("org") }` with `db()` returning the persisted string.
- `Action { EXPORT("export"), ERASE("erase") }` with `db()`.

**Gotchas a new engineer must know:**

- The entity stores **`String`** for `subjectType`/`action`, *not* the enum. Always go through `ErasureLog.SubjectType.USER.db()` / `ErasureLog.Action.ERASE.db()` when building a row, or you'll violate the CHECK constraint or store the wrong case.
- Despite the table name and the `Action.EXPORT` value, **export operations currently do NOT write an `ErasureLog` row** — only `eraseUser` and `deleteTenant` do (see `ErasureService`). `EXPORT` and `completedAt`-as-failure-marker are wired in the schema/enum but unused by the present `ErasureService`. The export path's evidence today lives only in `audit_log` (action `privacy.export`). This is a latent gap worth knowing.
- Both `deleteTenant` and `eraseUser` write `action = ERASE` even though tenant deletion is conceptually distinct; the `subjectType` (`org` vs `user`) is what distinguishes them.

---

### `ErasureLogRepository.java`

**Responsibility.** Spring Data JPA repository for `ErasureLog`.

```java
public interface ErasureLogRepository extends JpaRepository<ErasureLog, UUID> {
    List<ErasureLog> findBySubjectTypeAndSubjectIdOrderByRequestedAtDesc(String subjectType, UUID subjectId);
}
```

- Inherits `save(...)` (used by `ErasureService`) and the usual CRUD.
- The one derived finder returns a subject's DSAR history newest-first; it is backed by `idx_erasure_log_subject (subject_type, subject_id)` from the migration. **Note:** as of this writing nothing in this package *calls* the finder — it exists for DPO/audit tooling and tests. The `String subjectType` argument must be the lowercase DB form (use `SubjectType.db()`).

---

### `DataExportService.java`

**Responsibility.** Read-only assembly of a GDPR right-of-access / portability **export document** — a machine-readable `LinkedHashMap<String,Object>` the controller serialises to JSON. Two shapes: a **user export** (everything personal to one human) and an **org export** (the tenant's record plus the personal data it holds). `LinkedHashMap` is used throughout so JSON key order is stable/predictable for the consumer.

**Class:** `@Service public class DataExportService`. Constructor-injects seven collaborators: `UserRepository`, `OrganizationRepository`, `OrgMemberRepository`, `SubscriptionRepository`, `ApiKeyRepository`, `AuditLogRepository`, and a raw `JdbcTemplate`.

**Key constant:** `MAX_AUDIT_ROWS = 5000` — the cap on audit rows included in any export (passed as the page size to the audit search), so an export of a long-lived actor stays bounded.

#### Public methods

**`Map<String,Object> exportUser(UUID userId)`** — `@Transactional(readOnly = true)`

Loads the `User` (404 via `ApiException.notFound` if absent) and builds:

| Key | Source | Method |
|---|---|---|
| `exportType` | constant `"user"` | — |
| `generatedAt` | `OffsetDateTime.now()` | — |
| `subjectId` | the user id | — |
| `profile` | `users` | `userProfile(user)` |
| `orgMemberships` | `org_members` (+org names) | `membershipsForUser` |
| `ssoIdentities` | `sso_identities` (raw JDBC) | `ssoIdentitiesForUser` |
| `apiKeys` | API-key **metadata** for every org the user belongs to | `apiKeysForUserOrgs` |
| `auditEvents` | `audit_log` rows where the user is the **actor** | `auditEventsForActor` |

**`Map<String,Object> exportOrg(UUID orgId)`** — `@Transactional(readOnly = true)`

Loads the `Organization` (404 if absent) and builds an org `profile` (id, slug, name, status, created/updated) plus:

| Key | Source | Method |
|---|---|---|
| `members` | `org_members` (+ each member's email/full name) | `membersOfOrg` |
| `subscriptions` | `subscriptions` | `subscriptionsOfOrg` |
| `apiKeys` | API-key metadata for the org | `apiKeysOfOrg` |
| `ssoProviders` | `sso_providers` (config redacted) | `ssoProvidersOfOrg` |
| `auditEvents` | `audit_log` rows scoped to the org | `auditEventsForOrg` |

#### Private helpers — what they read and the security rationale

- **`userProfile(User)`** → id, email, fullName, status, superAdmin, createdAt, lastLoginAt. **Deliberately omits `passwordHash` and `tokenVersion`** (the comment calls them internal/secret). This omission is *by construction* — there is no filtering layer; you simply never `put` the secret fields.
- **`membershipsForUser(userId)`** → for each `OrgMember` (via `findByUserId`): orgId, orgName (a per-row `findById` lookup — N+1 by design, fine for an export), role, addedAt.
- **`ssoIdentitiesForUser(userId)`** → raw `JdbcTemplate` query against `sso_identities` selecting `id, provider_id, subject, created_at`. **Why raw JDBC?** The `SsoIdentityRepository` lives in the `sso` bucket and exposes no by-user finder; rather than edit another bucket's repository or add a schema change, this reads directly. Only non-secret columns are selected.
- **`apiKeysForUserOrgs(userId)`** → unions `apiKeysOfOrg(...)` for every org the user belongs to.
- **`auditEventsForActor(userId)`** → `auditLogRepository.search(null, userId, null, null, null, null, PageRequest.of(0, MAX_AUDIT_ROWS))`. The native search has a **fixed `ORDER BY occurred_at DESC`**, so the `Pageable` **must be unsorted** (a `Sort` would both fail to translate to a column and append a second `ORDER BY`). This is a sharp edge that bites if anyone "improves" it by passing a sorted `PageRequest`.
- **`membersOfOrg(orgId)`** → for each member: userId, email, fullName (per-row `findById`), role, addedAt. This is where an *org* export legitimately exposes other members' PII — the authz in the controller (OWNER/ADMIN or super-admin) is the gate.
- **`subscriptionsOfOrg(orgId)`** → id, planId, status, startsAt, endsAt, seats.
- **`apiKeysOfOrg(orgId)`** → id, orgId, name, **keyPrefix** (the display prefix, not the secret), scopes JSON, createdAt, lastUsedAt, revokedAt. **Deliberately omits `keyHash`.**
- **`ssoProvidersOfOrg(orgId)`** → raw JDBC against `sso_providers` selecting `id, type, enabled, created_at`. **`config_json` / `client_secret_enc` deliberately omitted** — they may contain secrets.
- **`auditEventsForOrg(orgId)`** → `auditLogRepository.searchForOrg(orgId, null...)`, same `MAX_AUDIT_ROWS` cap and unsorted-pageable rule.
- **`auditRow(AuditLog)`** → flattens an audit row to id, action, actorUserId, actorOrgId, targetType, targetId, **payloadJson, ipAddress**, outcome, occurredAt. Note: an export *will* surface `payloadJson` and `ipAddress` — which is exactly the PII that erasure later scrubs.
- **`str(OffsetDateTime)`** → null-safe `toString()` helper used everywhere for timestamps.

**Collaborators / who calls it:** called only by `DataPrivacyController.export(...)`. Reads through six repositories + `JdbcTemplate`.

**Gotchas:**

- **No tenant-scoping inside the service.** `exportUser`/`exportOrg` trust that the controller already authorized the caller. Never call these from a new endpoint without replicating the `canExportUser`/`canExportOrg` checks.
- **`readOnly = true`** means no writes can sneak in; combined with the per-row lookups it is read-amplifying but safe.
- The two **raw-JDBC reads** are intentional cross-bucket reads; if the `sso_*` schemas change column names, these break silently at runtime (no compile-time check).

---

### `ErasureService.java`

**Responsibility.** The heart of the package: **right-to-erasure** (`eraseUser`) and **tenant off-boarding** (`deleteTenant`). Implements the *pseudonymise, don't destroy the trail* model in a single transaction each, and is the only sanctioned writer of the `audit_log` redaction path.

**Class:** `@Service public class ErasureService`. Injects `UserRepository`, `OrgMemberRepository`, `UserMfaRepository`, `ErasureLogRepository`, `AuditLogRepository`, `SessionRevocationStore`, and a raw `JdbcTemplate`.

#### `ErasureLog eraseUser(UUID userId, UUID requestedBy)` — `@Transactional`

Erases one human data subject. **Idempotent in effect**: re-running on an already-erased user simply re-applies the redaction (the placeholder email is deterministic, so the unique constraint isn't violated on a re-run). Returns the ledger row.

The five ordered steps (all in one transaction):

1. **Pseudonymise the identity.** Load the user (404 if absent), then:
   - `email = redactedEmail(userId)` → `"erased+" + userId + "@redacted.invalid"` — *stable, per-id, non-reversible*. Being per-id keeps the `CITEXT UNIQUE` email constraint satisfiable across many erased users.
   - `fullName = null`, `passwordHash = null`, `status = DELETED`.
2. **Revoke all live sessions.** Bump the durable per-user token-version: `newVersion = tokenVersion + 1`, set it on the entity, `userRepository.save(user)`. Then **best-effort** write-through to the cache: `revocationStore.setTokenVersion(userId, newVersion)` inside a `try/catch` that only logs a warning on failure — **the DB column is authoritative; the cache is an accelerator.** `JwtAuthFilter` rejects any JWT whose `tv` claim no longer matches, so all existing tokens for this user are now dead.
3. **Delete authentication side-data.** `DELETE FROM sso_identities WHERE user_id = ?` (raw JDBC, returns a count) and `userMfaRepository.deleteByUserId(userId)` (drops TOTP enrollment).
4. **Scrub PII from retained audit rows.** `redactSubjectAudit(userId)` (see below). The comment is explicit about *why this is necessary*: the raw client IP is always stored, and some writers (e.g. the SCIM provisioned-event payload) put the **raw email into `payload_json`** — so an earlier assumption that "the audit writer already masks emails" did not hold. The `audit_log` rows themselves are **retained** (the trail is append-only/tamper-evident), only their free-form PII columns are nulled/replaced.
5. **Write the PII-free ledger row.** `ErasureLog` with `subjectType = USER.db()`, `action = ERASE.db()`, `requestedBy`, and both `requestedAt`/`completedAt` set to `now()` — committed in the same transaction.

Finally logs counts (`ssoIdentitiesDeleted`, `auditRowsRedacted`) for operational evidence.

#### `ErasureLog deleteTenant(UUID orgId, UUID requestedBy)` — `@Transactional`

Tenant off-boarding. Returns the ledger row.

1. **Existence check** via `SELECT count(*) FROM organizations WHERE id = ?` (raw JDBC) — `ApiException.notFound` if zero/null. Deliberately captures *no* PII.
2. **Erase each member's PII inline.** Iterates `orgMemberRepository.findByOrgId(orgId)` and calls `eraseUserPiiInline(userId)` per member, counting `membersErased`. The comment notes a deliberate policy choice: it erases a member's PII **regardless of other memberships**, because tenant deletion is a destructive, super-admin-gated admin action (cross-tenant members are rare in this model). *A new engineer must understand this can affect a user who is also a member of another, surviving tenant.*
3. **Drop org SSO providers.** `DELETE FROM sso_providers WHERE org_id = ?` (also removes provider-scoped secrets at rest).
4. **Mark the org `DELETED`.** `UPDATE organizations SET status = 'DELETED', updated_at = now() WHERE id = ?` (string matches `Organization.Status` + the CHECK). The org row, its subscriptions, and audit rows are **kept** for the compliance/billing record — *no hard delete*.
5. **Write the ledger row** with `subjectType = ORG.db()`, `action = ERASE.db()`.

#### Private internals

**`void eraseUserPiiInline(UUID userId)`** — the per-member erasure used by `deleteTenant`. Mirrors steps 1–4 of `eraseUser` (pseudonymise, token-version bump + best-effort cache, delete sso_identities + MFA, `redactSubjectAudit`) but **does not** write its own `ErasureLog` row — the tenant deletion writes a single org-level ledger row instead. Uses `findById(...).ifPresent(...)` so a dangling membership pointing at a missing user is silently skipped rather than failing the whole tenant teardown.

**`int redactSubjectAudit(UUID userId)`** — the crux of the audit-redaction-within-the-erasure-transaction story:

```java
private int redactSubjectAudit(UUID userId) {
    jdbc.execute("SET LOCAL app.audit_redaction = 'on'");   // tx-scoped opt-in
    return auditLogRepository.redactPiiForActor(userId);    // the one sanctioned UPDATE
}
```

- `SET LOCAL` makes the GUC **transaction-scoped** — it is automatically rolled back at commit/rollback, so the redaction privilege never leaks to other transactions on the same pooled connection. Because both the `SET LOCAL` and the redaction `UPDATE` run through the **same transaction-bound JDBC connection**, the GUC is in force exactly when the UPDATE fires.
- The `audit_log` `BEFORE UPDATE` trigger (`audit_log_block_modifications`, redefined in `18-webhook-fanout-integrity.sql`) rejects *every* UPDATE **unless** `current_setting('app.audit_redaction', true) = 'on'` **and** no identity/integrity column changes. So this is the **only** code path in the whole application allowed to mutate `audit_log`.

**`static String redactedEmail(UUID userId)`** → `"erased+" + userId + "@redacted.invalid"`. Stable + non-reversible + unique-per-id; `@redacted.invalid` uses the reserved `.invalid` TLD so the address can never resolve or be mailed.

**Collaborators / who calls it:** called by `DataPrivacyController.erase` and `.deleteTenant`. Calls `AuditLogRepository.redactPiiForActor`, `SessionRevocationStore.setTokenVersion`, repositories for users/members/MFA, and raw `JdbcTemplate` for `sso_identities`, `sso_providers`, and `organizations`.

**Concurrency / transaction gotchas a new engineer must know:**

- **Everything is one transaction.** If any step (including the ledger save or the audit redaction) throws, the *entire* erasure rolls back — including the `users` pseudonymisation and the `SET LOCAL` GUC. That is the desired atomicity, but it means a failing audit redaction will *undo* an otherwise-complete erasure; treat redaction failures as hard failures.
- **The token-version cache write is the only intentionally non-atomic side-effect** (best-effort, swallowed). On rollback the DB token-version reverts but a cache value may have been written — harmless because the cache is only a fast-path and the DB is authoritative; a later read reconciles.
- **`SET LOCAL` only works because the redaction UPDATE shares the same connection/transaction.** If someone refactors `redactSubjectAudit` to run on a different connection (e.g. a `REQUIRES_NEW` sub-transaction or a separate `JdbcTemplate`/datasource), the GUC will not be visible to the UPDATE and the trigger will reject it with `audit_log is immutable: UPDATE operation is not permitted`.
- **`redactPiiForActor` only matches `actor_user_id = :actorId`.** Audit rows that *reference* the subject as a *target* (but were authored by someone else) are **not** scrubbed — only rows the subject *authored*. This is by design (the trail of actions *by* the subject is what carries their PII) but is a subtlety to be aware of when reasoning about completeness of erasure.
- **`deleteTenant` does not cascade-erase by SQL.** It relies on the per-member loop. A user with no `org_members` row but org-owned PII elsewhere would not be reached.

---

### `DataPrivacyController.java`

**Responsibility.** The REST surface for data-subject rights under `"/api/v1/privacy"`. Three endpoints: `GET /export`, `POST /erase`, `POST /tenant/{orgId}/delete`. It performs **explicit, in-method authorization** (not `@PreAuthorize`) and emits the canonical `audit_log` row for each request, suppressing the `AuditInterceptor`'s automatic write.

**Class:** `@RestController @RequestMapping("/api/v1/privacy")`. Injects `DataExportService`, `ErasureService`, `OrgService`, `AuditWriter`, `TrustedProxyResolver`.

#### Why authz is in-method, not annotation-based

The Javadoc is explicit: the export endpoint's subject is chosen by query parameter (`userId` **XOR** `orgId`), and the allowed caller differs per subject — "super_admin OR the subject themselves OR an org admin". That branching cannot be expressed cleanly in a single `@PreAuthorize` SpEL, so each method does it by hand. Each method also **sets the audit action *before* any forbidden throw**, so a denial is still captured (by the `GlobalExceptionHandler`/`AuditInterceptor` fallback, which reads `AuditContext`).

#### Endpoints

**`GET /export` → `Map<String,Object> export(@RequestParam UUID userId?, @RequestParam UUID orgId?, HttpServletRequest)`**

Control flow:
1. `me = SecurityUtils.requireUser()` (401 if unauthenticated).
2. **XOR check:** `if ((userId == null) == (orgId == null)) → 400` "Exactly one of userId or orgId must be provided". (Both-null and both-present both fail.)
3. User branch: `AuditContext.set("privacy.export")` + `setTarget("user", userId)`; `if (!canExportUser(me, userId)) → 403`; `result = exportService.exportUser(userId)`; `recordSuccess("privacy.export", "user", userId, ..., {subjectType:"user"})`.
4. Org branch: symmetric, `canExportOrg`, `exportService.exportOrg`, `recordSuccess` with `subjectType:"org"`.
5. Return the export map (Spring serialises to JSON).

> Because `export` is a `GET` (not a mutating mapping), the `AuditInterceptor`'s `@AfterReturning` pointcut (`mutatingEndpoint()` = POST/PUT/PATCH/DELETE) does **not** fire for it — so the success row comes solely from the explicit `recordSuccess` call here. The `markRecorded()` it sets is what makes the `GlobalExceptionHandler` fallback skip a duplicate on the denial path.

**`POST /erase` → `ResponseEntity<Map<String,Object>> erase(@RequestBody EraseRequest body, HttpServletRequest)`**

1. `AuditContext.set("privacy.erase")` then `me = requireUser()`.
2. Validate `body != null && body.userId() != null` (400 otherwise). (`EraseRequest` also carries `@NotNull` on `userId`.)
3. `AuditContext.setTarget("user", userId)`, then `requireSuperAdmin(me)` (403 if not super-admin) — **super-admin only**.
4. `ledger = erasureService.eraseUser(userId, me.userId())`.
5. `recordFailClosedSuccess("privacy.erase", "user", userId, ..., {erasureLogId})` — writes a **fail-closed** SUCCESS audit row.
6. Returns `200 {status:"erased", userId, erasureLogId}`.

**`POST /tenant/{orgId}/delete` → `ResponseEntity<Map<String,Object>> deleteTenant(@PathVariable UUID orgId, HttpServletRequest)`**

1. `AuditContext.set("privacy.tenant.deleted")` + `setTarget("org", orgId)`, `me = requireUser()`, `requireSuperAdmin(me)`.
2. `ledger = erasureService.deleteTenant(orgId, me.userId())`.
3. `recordFailClosedSuccess("privacy.tenant.deleted", "org", orgId, ..., {erasureLogId})`.
4. Returns `200 {status:"deleted", orgId, erasureLogId}`.

#### Authorization helpers

- **`canExportUser(me, userId)`** → `true` if `me.superAdmin()`; else allowed only if the caller **is the subject** and is a *human* principal: `!me.isApiKey() && userId.equals(me.userId())`. An API-key principal has no `userId`, so it can never self-export a user.
- **`canExportOrg(me, orgId)`** → `true` if super-admin; **API-key principals are rejected outright** (`if (me.isApiKey()) return false`); null `userId` rejected; otherwise `orgService.roleOf(orgId, me.userId())` must be `OWNER` or `ADMIN`. (`roleOf` returns `Optional<OrgMember.Role>`; `.orElse(false)` denies non-members.)
- **`requireSuperAdmin(me)`** → throws `ApiException.forbidden("Super-admin required")` unless `me.superAdmin()`. Used by both mutating endpoints.

#### Audit helpers — the duplicate-suppression mechanism

- **`recordSuccess(...)`** — resolves client IP via `proxyResolver.resolveClientIp(request)`, then `auditWriter.record(actorId, apiKeyOrgId-if-apikey, action, targetType, targetId, payload, ip, SUCCESS, /*failClosed=*/false)` and `AuditContext.markRecorded()`. Used by the (non-mutating) export path; **fail-open** (best-effort, `REQUIRES_NEW`, swallowed on error).
- **`recordFailClosedSuccess(...)`** — identical but with **`failClosed = true`**, so the audit INSERT runs **inline in the caller's transaction** and any failure **propagates and rolls back** the business operation. This is the right coupling for high-value erasure actions: you must not report a successful erasure whose audit record failed to persist. Also calls `markRecorded()`.
- **Why `markRecorded()` matters:** `AuditInterceptor.afterMutating`/`afterThrowing` both early-return when `AuditContext.isRecorded()`. Since `erase` and `deleteTenant` are POST mappings, the interceptor *would* otherwise emit a second SUCCESS row; `markRecorded()` suppresses that duplicate. (The interceptor's `finally { AuditContext.clear() }` then resets the thread-local for the next request.)
- **`actorId(me)`** → `me.userId()` (null for an API-key principal — though both mutating endpoints require super-admin, which an API key could theoretically be).

#### Nested type

- **`record EraseRequest(@NotNull UUID userId)`** — the `POST /erase` body. `@NotNull` is belt-and-suspenders alongside the explicit null check in the method.

**Collaborators / who calls it:** invoked by Spring MVC for the three routes; calls `DataExportService`, `ErasureService`, `OrgService.roleOf`, `AuditWriter.record`, `TrustedProxyResolver.resolveClientIp`, and the static `SecurityUtils`/`AuditContext`.

**Gotchas a new engineer must know:**

- **Order matters:** the audit `action`/`target` are set on `AuditContext` *before* the `requireSuperAdmin`/`canExport*` throws, specifically so a 403 is still attributed to the right action in the trail. Do not move authz before `AuditContext.set(...)`.
- The **export endpoint is `GET`**, so it is *not* covered by the `AuditInterceptor`'s mutating-endpoint pointcut — its audit comes only from `recordSuccess`. A denied export throws *before* `recordSuccess` runs, so its DENIED row depends on the `GlobalExceptionHandler` fallback reading the pre-set `AuditContext`.
- **API-key callers**: `canExportUser` and `canExportOrg` both explicitly handle the api-key case (no user id; org-export forbidden). When recording, the controller passes `me.apiKeyOrgId()` as the actor-org only for api-key principals.
- Mutating endpoints use **fail-closed** auditing — a broken audit insert will turn a "successful" erase into a 5xx and roll it back. That is intentional; don't "fix" it to fail-open.

---

## End-to-end flows (quick reference)

**Erase a user (`POST /api/v1/privacy/erase`):**

```
requireUser → super-admin check → ErasureService.eraseUser (one tx):
  pseudonymise users row (email/name/hash/status)
  bump token_version  →  best-effort cache write
  DELETE sso_identities ; delete user_mfa
  SET LOCAL app.audit_redaction='on'  →  UPDATE audit_log (scrub payload_json + ip_address)
  INSERT erasure_log (PII-free ledger)
→ recordFailClosedSuccess (inline audit row, rolls back tx on failure) → 200
```

**Tenant off-boarding (`POST /api/v1/privacy/tenant/{orgId}/delete`):** same shape, but iterates members calling `eraseUserPiiInline` (no per-member ledger), drops `sso_providers`, flips `organizations.status='DELETED'`, and writes one org-scoped `erasure_log` row.

**Export (`GET /api/v1/privacy/export?userId=… | orgId=…`):** authz per-subject → `DataExportService` assembles a secret-free JSON `Map` (audit rows capped at 5000, secret columns omitted by construction) → fail-open `recordSuccess` audit row.

## Cross-package dependencies at a glance

| Depends on | For |
|---|---|
| `com.example.cp.audit` (`AuditWriter`, `AuditLogRepository`, `AuditOutcome`) | Emitting the canonical audit row; the GUC-gated `redactPiiForActor` redaction. |
| `com.example.cp.common` (`AuditContext`, `SecurityUtils`, `AuthenticatedUser`, `TrustedProxyResolver`, `ApiException`, `Ids`) | Auth/principal access, audit-context thread-local + duplicate suppression, client-IP resolution, error mapping, id generation. |
| `com.example.cp.auth.SessionRevocationStore` | Best-effort cache write-through of the bumped token-version (session revocation). |
| `com.example.cp.orgs` (`OrgService`, `OrgMember(.Role)`, `OrganizationRepository`, `OrgMemberRepository`) | Org-export authz (`roleOf`), membership/org reads, per-member tenant erasure. |
| `com.example.cp.users` / `subscriptions` / `apikeys` / `mfa` repositories | Reading export data; pseudonymising the user row; deleting MFA enrollment. |
| DB triggers in `08-audit.sql` / `18-webhook-fanout-integrity.sql` | The append-only `audit_log` guarantee + its single, GUC-gated GDPR-redaction exception. |
