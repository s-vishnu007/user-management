# `com.example.cp.licenses` — License issuance, offline verification artifacts, activation & revocation

> Module: `control-panel-api` · Package: `com.example.cp.licenses`
> Source root: `control-panel-api/src/main/java/com/example/cp/licenses/`
> Files documented: **16**

## Module overview

This package is the heart of the product's reason to exist: it **mints, persists, distributes, tracks, and revokes the Ed25519-signed JWT licenses** that customer Docker apps verify *offline* via the bundled `license-verifier` SDK. A control-panel operator (or an automation, scoped by tenant) calls `POST /subscriptions/{id}/licenses`; the package resolves the subscription's plan + org + entitlements into a `JWTClaimsSet`, signs it with the currently-active Ed25519 key, and stores **both** a lightweight metadata row (`LicenseToken`) and the **exact signed artifact** (`LicenseArtifact`). The customer downloads a `.lic` envelope (JSON wrapping the raw JWT) and drops it into their app, which verifies the signature against `/.well-known/jwks.json` with **no network call back to the control panel**.

Because verification is offline, the package owns the *only* two enforcement channels that work after a license is in the field:

1. **Expiry** — baked into the JWT `exp` claim; the verifier enforces it autonomously, and `LicenseLifecycleScheduler` keeps the server's own status column in sync and fires warning/expiry events.
2. **Revocation** — a signed **CRL** (certificate-revocation list, itself a JWS) served from `GET /licenses/crl`, which the verifier polls to learn that an otherwise-valid, unexpired license has been killed (`LicenseRevocationService` + `CrlController`).

A third runtime signal, the **heartbeat / phone-home** (`LicenseHeartbeatController` + `ActivationService`), is *advisory* seat-counting and liveness tracking — it does not gate the offline verifier, but it lets the control panel enforce seat limits and reject heartbeats from suspended subscriptions.

The package is also a textbook of post-audit hardening. Almost every method carries a `Pn-x` audit-finding reference. The recurring themes are: **GET must be a pure read** (P1-4 — `/download` never re-mints), **concurrent writes must not resurrect a revoked/expired token** (P1-8 guarded conditional UPDATEs + `@Version`), **seat-limit TOCTOU is closed with a pessimistic row lock** (P1-9), **the public, unauthenticated CRL endpoint must not be a signing-DoS amplifier** (P2 caching), and **unbounded growth / integer overflow are clamped** (P3).

### How it fits the bigger picture

```
        ┌──────────────────────────────────────────────────────────────────┐
        │  control-panel-api  (this package: com.example.cp.licenses)        │
        │                                                                    │
   operator ─POST /subscriptions/{id}/licenses─►  LicenseController          │
        │        │                                    │                      │
        │        ▼                                    ▼                      │
        │   LicenseClaimsBuilder ◄── plans/orgs/subscriptions services       │
        │        │  (claims)                                                 │
        │        ▼                                                           │
        │   LicenseIssuer ──► KeyService.getActiveSigningKeyPair()           │
        │        │            JwsSigner.sign(...)  (Ed25519, com.example.cp.keys)
        │        ├──► LicenseToken      (metadata row)                       │
        │        ├──► LicenseArtifact   (exact signed JWT, for pure-read DL) │
        │        └──► OutboxPublisher   "LicenseIssued"                      │
        │                                                                    │
   customer ─GET /licenses/{jti}/download─► LicenseFileBuilder → .lic JSON   │
   customer ─GET /licenses/crl───────────► CrlController (cached signed JWS) │
   app/node ─POST /licenses/{jti}/heartbeat─► ActivationService (seats)      │
        │   LicenseLifecycleScheduler (every 5m): expire + warn              │
        └──────────────────────────────────────────────────────────────────┘
                              │  jwks.json + crl
                              ▼
        ┌──────────────────────────────────────────────────────────────────┐
        │  customer Docker app  +  license-verifier SDK (offline)            │
        │  verifies JWT signature, exp, and consults the CRL — no callback   │
        └──────────────────────────────────────────────────────────────────┘
```

Upstream collaborators (outside this package): `com.example.cp.keys` (`KeyService`, `JwsSigner` — Ed25519 key management & signing), `com.example.cp.subscriptions` (`SubscriptionService`, `SubscriptionRepository`, `OutboxPublisher`), `com.example.cp.plans` (`PlanService`, `PlanRepository` — permissions/features/seats/TTL), `com.example.cp.orgs` (`OrganizationRepository`), and `com.example.cp.common` (`ApiException`, `AuditContext`, `Ids`, `SecurityUtils`, `PageRequestParams`, `TrustedProxyResolver`). Downstream consumers: the React `admin-ui`, the `license-verifier` SDK, and the `sample-docker-app`.

---

## File-by-file reference

The files cluster into six functional groups. They are documented in the order an engineer would naturally trace a request: **issuance pipeline → entities → repositories → controllers → activation/seat-counting → revocation & lifecycle → DTO**.

---

### Group 1 — The issuance pipeline

#### `LicenseClaimsBuilder.java`

**Responsibility.** Translate a `Subscription` into the canonical, signed-payload **claim set** for a license JWT. This is the single place that decides *what a license says*: who it's for, what it grants, and when it expires. It deliberately knows nothing about signing or persistence — it only produces a `JWTClaimsSet` plus the denormalized metadata the rest of the pipeline needs.

**Public/important API.**

| Member | Purpose |
|---|---|
| `BuiltClaims build(Subscription sub, Integer ttlDaysOverride, List<String> audienceOverride)` | The core method. Resolves plan + org + entitlements, computes expiry, builds the claim set. |
| `record BuiltClaims(jti, issuedAt, expiresAt, planCode, orgName, orgSlug, audience, claims)` | The build output. Carries the `JWTClaimsSet` plus denormalized fields the issuer/artifact/file-builder need without re-querying. Helpers `issuedAtInstant()` / `expiresAtInstant()`. |
| `List<String> defaultAudienceList()` | Returns a fresh mutable copy of the single default audience; used by callers that want to start from the default and tweak. |
| `static final int MAX_TTL_DAYS = 36_500` | ~100-year clamp ceiling (see below). |
| `static final int CLAIM_VERSION = 1` | Embedded wire-contract version, stamped into every license as the `version` claim. Bump when the claim shape changes so verifiers can branch. |

**Config it reads.** `@Value("${app.signing.issuer}")` → the `iss` claim and CRL issuer; `@Value("${app.signing.default-audience}")` → the fallback `aud`.

**What `build` actually does (control flow):**

1. Load the `Plan` (by `sub.getPlanId()`) and `Organization` (by `sub.getOrgId()`); each missing → `ApiException.notFound`.
2. Compute **entitlements**: take the plan's base permissions (`planService.getPermissions`) and base features (`planService.getFeatures`), then layer subscription-level overrides via `subService.resolveEntitlements(...)`. So the license reflects *effective* grants, not just the plan defaults.
3. Compute **expiry** (the subtle part):
   - If `ttlDaysOverride != null && > 0`: `exp = now + clampTtlDays(ttlDaysOverride)`.
   - Otherwise: take the plan default (`now + clampTtlDays(plan.getDefaultTtlDays())`), but **honor the subscription end date if it is sooner** — a license never outlives its paid subscription. This is the `sub.getEndsAt().isBefore(planDefault) ? endsAt : planDefault` line.
   - Guard: if the computed `exp` is not after `now` → `ApiException.badRequest` ("Computed expiry is in the past"). Catches e.g. an `endsAt` already in the past.
4. Mint a `jti` via `buildJti()` → `"lic_" + UUIDv7-hex-without-dashes`. Human-recognizable prefix, collision-resistant body.
5. Resolve audience: the override list if non-empty, else `List.of(defaultAudience)`.
6. Build a `customer` map: `{org_name, contact_email:null}` — note `contact_email` is a known TODO placeholder.
7. Assemble the `JWTClaimsSet`: standard `iss/aud/sub(=orgId)/jti/iat/nbf/exp` plus custom claims `subscription_id`, `plan` (the plan **code**, not id), `permissions`, `features`, `seats`, `customer`, `version`.

**Gotchas & why-it-exists notes:**
- **`clampTtlDays` (P3):** clamps a requested TTL to `[1, MAX_TTL_DAYS]`. A pathological value (e.g. `Integer.MAX_VALUE`) passed to `OffsetDateTime.plusDays` would throw `DateTimeException` → a raw 500. The clamp turns that into a sane bounded value. A non-positive value falls back to `1` (defensive — the `issue` caller already filters `ttlDaysOverride > 0`, but this also guards a misconfigured `plan.getDefaultTtlDays()`).
- `nbf` (`notBeforeTime`) equals `iat` — licenses are valid immediately, no future-dating.
- `sub` (subject) is the **org id**, while `subscription_id` is a separate custom claim — don't confuse them.
- The `plan` claim is the plan **code** (stable human string), deliberately, so the verifier can branch on `"pro"` rather than a DB UUID.

---

#### `LicenseIssuer.java`

**Responsibility.** The transactional orchestrator that turns built claims into a *signed, persisted, audited, event-emitting* license. This is the only writer of `license_tokens` + `license_artifacts` rows for issuance.

**Public API.**

| Member | Purpose |
|---|---|
| `IssuedLicense issue(UUID subscriptionId, Integer ttlDaysOverride, List<String> audienceOverride)` | Standard license (delegates to the 4-arg form with `STANDARD`). |
| `IssuedLicense issueTrial(UUID subscriptionId, Integer ttlDaysOverride, List<String> audienceOverride)` | TRIAL license; TTL = positive override else `app.licensing.trial-ttl-days` (default 14). |
| `IssuedLicense issue(UUID, Integer, List<String>, LicenseToken.LicenseType)` | The real implementation. |
| `record IssuedLicense(jti, jwt, issuedAt, expiresAt, planCode, orgName, orgSlug, kid)` | The fully-formed result; the same shape that `LicenseArtifact` round-trips and `LicenseFileBuilder` consumes. |

**Config.** `@Value("${app.licensing.trial-ttl-days:14}")`.

**Collaborators.** `KeyService` (active key), `JwsSigner` (Ed25519 sign), `LicenseClaimsBuilder`, `LicenseTokenRepository`, `LicenseArtifactRepository`, `SubscriptionService` (status check), `OutboxPublisher`. Called by `LicenseController.issue`.

**Control flow of the 4-arg `issue` (all under one `@Transactional`):**

1. `subService.get(subscriptionId)` and **reject** unless `status == ACTIVE` → `ApiException.badRequest`. You cannot mint against a suspended/cancelled/expired subscription.
2. `claimsBuilder.build(...)` → `BuiltClaims`.
3. `keyService.getActiveSigningKeyPair()` → `ActiveKey(kid, privateKey, publicKey)`.
4. `jwsSigner.sign(built.claims(), "license+jwt", active)` → compact Ed25519 JWS string. The `typ` header is `license+jwt`.
5. `fingerprint = sha256TruncatedHex(jwt, 32)` — first 32 hex chars of SHA-256 of the serialized JWT. A short, indexable, non-reversible identifier for the exact artifact (e.g. for log correlation / dedup spotting). On any hashing exception it returns `null` rather than failing issuance.
6. Persist a `LicenseToken` (status `ACTIVE`, the resolved `LicenseType`, `kid`, `jti`, `iat`, `exp`, `fingerprint`).
7. **Persist the exact `LicenseArtifact`** (`artifactRepo.save(LicenseArtifact.from(issued))`) in the *same* transaction. This is the linchpin of audit **P1-4**: because the signed bytes are stored atomically with the token row, the later `GET /download` is a pure read and never needs to re-sign.
8. Set `AuditContext` (`license.issued`, target `license_token:jti`, payload with subscription/plan/kid/type).
9. `outbox.publish("license_token", jti, "LicenseIssued", {...})` — same transaction, transactional-outbox pattern so the event commits iff the rows commit.

**Gotchas:**
- The `licenseType` is defensively normalized: `licenseType == null ? STANDARD : licenseType`.
- `sha256TruncatedHex` swallowing exceptions to `null` is intentional — a hashing hiccup must never abort a valid issuance; the fingerprint is auxiliary metadata.
- Everything is one transaction: token + artifact + audit-context-driven audit row + outbox event are atomic.

---

#### `LicenseFileBuilder.java`

**Responsibility.** Wrap a raw signed JWT in the **`.lic` envelope** JSON contract the customer's Docker app expects, and suggest a download filename. Pure formatting; no DB, no signing.

**Public API.**

| Method | Purpose |
|---|---|
| `byte[] buildEnvelopeBytes(LicenseIssuer.IssuedLicense issued, String notesOverride)` | Builds the pretty-printed JSON envelope bytes. |
| `String suggestedFilename(LicenseIssuer.IssuedLicense issued)` | Produces `<org-slug>-<short-jti>.lic`. |

**Config.** `@Value("${app.signing.issuer}")` — used only in the default `notes` string.

**The `.lic` envelope shape** (a `LinkedHashMap`, so field order is stable/deterministic):

```json
{
  "license":    "<the raw compact Ed25519 JWS>",
  "issued_at":  "2026-06-15T...Z",
  "customer":   "Example Corp",
  "plan":       "pro",
  "expires_at": "2026-09-15T...Z",
  "notes":      "Generated by <issuer>. Drop this file at /etc/app/license.lic."
}
```

**Gotchas:**
- `notesOverride` is used only when non-null and non-blank; otherwise the default instruction string is generated. `LicenseController.download` passes `null`, so downloads always get the default note.
- `suggestedFilename`: slug defaults to `"license"` if null; the `jti` is shortened by stripping the `lic_` prefix and taking the next 8 hex chars (`substring(4, 12)`) only when it actually starts with `lic_` and is long enough. So `lic_0192abcd...` → `example-0192abcd.lic`.
- `JsonProcessingException` is wrapped into a `RuntimeException` — serialization of a fixed-shape map essentially never fails, but it's surfaced rather than swallowed.
- The unused `private static byte[] utf8(String)` helper is dead code (`@SuppressWarnings("unused")`) — a leftover; ignore it.

---

### Group 2 — Persistence entities

#### `LicenseToken.java`

**Responsibility.** JPA entity for table `license_tokens` — the **metadata/state record** for each issued license (its lifecycle status, seat last-seen, revocation/expiry/warning markers). It does **not** store the JWT (that's `LicenseArtifact`).

**Shape:** Lombok `@Getter/@Setter/@Builder/@NoArgsConstructor/@AllArgsConstructor`.

| Column | Notes |
|---|---|
| `id` UUID `@Id` | Primary key (UUIDv7 from `Ids.newId()`). |
| `version` long `@Version` | **Optimistic lock.** Prevents a heartbeat last-seen flush from rewriting a concurrently-committed revocation/expiry back to ACTIVE (lost update, **P1-8**). |
| `jti` (unique, len 64) | The license id used everywhere in the API path. |
| `subscription_id` UUID | Owning subscription (and transitively, org). |
| `kid` (len 64) | The signing-key id used — important for key rotation / which JWKS entry verifies it. |
| `issued_at`, `expires_at` | Mirror the JWT `iat`/`exp`. |
| `revoked_at`, `revoke_reason` | Set on revocation. |
| `fingerprint` (len 128) | SHA-256-truncated hash of the JWT. |
| `last_seen_at`, `last_seen_ip` (len 45) | Most recent heartbeat across all nodes. `45` chars = max IPv6 textual length. |
| `expiring_warned_at` | **Durable dedup marker** for the `license.expiring` event — NULL = never warned (P2). |
| `status` enum STRING | `ACTIVE` / `REVOKED` / `EXPIRED`. |
| `license_type` enum STRING (len 20, `@Builder.Default = STANDARD`) | `STANDARD` / `TRIAL`. |

**Enums.** `Status { ACTIVE, REVOKED, EXPIRED }`, `LicenseType { STANDARD, TRIAL }`.

**Gotchas:**
- `@Builder.Default` on `licenseType` matters: without it, Lombok's builder would leave the field `null` instead of `STANDARD` when not set.
- Status transitions are deliberately **not** done via full-entity `save()` in the hot paths — they go through guarded conditional UPDATEs (see the repository). `EXPIRED` is set by the scheduler; `REVOKED` by the revocation service; `ACTIVE` only ever at issue time.

---

#### `LicenseArtifact.java`

**Responsibility.** JPA entity for table `license_artifacts` — stores the **exact signed JWT** plus the envelope metadata, keyed by `jti`. Immutable after issue (the JWT for a jti never changes).

**Why it exists (audit P1-4):** the *previous* design cached the JWT in memory and **re-minted** a brand-new license on a cache miss during download. That meant a read-only principal could mint licenses it had no right to issue (new jti, new row, new outbox event, zero audit) simply by hitting `/download` for a jti that had aged out of the cache. Persisting the artifact at issue time makes `/download` a *pure read*.

**Shape:** `@Id` is `jti` (String, len 64) — so this table is keyed by jti, not a surrogate UUID. Columns: `jwt` (the raw JWS), `kid`, `plan_code`, `org_name`, `org_slug`, `issued_at`, `expires_at`, `created_at`.

**Methods:**
- `LicenseIssuer.IssuedLicense toIssuedLicense()` — rebuilds the `IssuedLicense` view that `LicenseFileBuilder` consumes on download.
- `static LicenseArtifact from(IssuedLicense)` — package-private factory used by `LicenseIssuer` at save time; stamps `created_at = now`.

**Gotcha:** there is no update path and no `findById`-by-UUID — lookups go through `LicenseArtifactRepository.findByJti`.

---

#### `LicenseActivation.java`

**Responsibility.** JPA entity for table `license_activations` — one row **per `(jti, node_id)`**, i.e. per seat/node that has phoned home. Tracks `first_seen_at` (set once) and `last_seen_at`/`last_seen_ip` (refreshed each beat).

**Shape:** `@Id` surrogate `id` UUID; `jti` (len 64, logical FK to `license_tokens.jti`), `node_id` (len 190, app-reported host/instance id), `first_seen_at`, `last_seen_at`, `last_seen_ip` (len 45).

**Seat semantics (documented in the Javadoc):** a seat is "active" iff its `last_seen_at` is within the configurable lease window. Rows older than the window are treated as *released* seats and stop counting against the limit — there is no explicit deactivation; seats lapse by silence.

**Gotcha:** there must be a unique constraint on `(jti, node_id)` at the DB level — `ActivationService` relies on catching `DataIntegrityViolationException` when two concurrent first-beats for the same node race to insert. The `node_id` `length = 190` is a classic MySQL-utf8mb4-index-friendly value carried over even on Postgres.

---

### Group 3 — Repositories

#### `LicenseTokenRepository.java`

**Responsibility.** Spring Data JPA repository for `LicenseToken` and the package's most security-sensitive file — it holds the **guarded conditional UPDATEs and the pessimistic lock** that make concurrency correct.

**Plain finders:**
- `Optional<LicenseToken> findByJti(String)` — the everyday lookup.
- Paged/unpaged `findBySubscriptionId[AndStatus]OrderByIssuedAtDesc(...)` — back the `GET /licenses` listing.
- `findBySubscriptionIdAndStatus(subId, status)` — used to cascade revocation.
- `findByStatusOrderByIssuedAtDesc`, `findByStatusOrderByRevokedAtAsc`, `findByStatusAndRevokedAtAfterOrderByRevokedAtAsc`, `findByStatusAndExpiresAtAfterOrderByRevokedAtAsc`, `findByStatusAndExpiresAtLessThanEqual` — feed the CRL, `/revoked`, and the lifecycle sweeper.
- `countByStatus`, `@Query MAX(revokedAt)` `maxRevokedAt` — the cheap CRL cache key.

**The concurrency-critical members:**

| Method | What it does & why |
|---|---|
| `@Lock(PESSIMISTIC_WRITE) findByJtiForUpdate(jti)` | `SELECT ... FOR UPDATE` on the token. **P1-9 TOCTOU fix:** serializes the count-seats / insert-activation sequence so N concurrent first-beats from distinct nodes can't each read `limit-1` and all insert past the cap. Used only by the heartbeat. |
| `int touchLastSeenIfActive(jti, seenAt, seenIp, active)` | Guarded `UPDATE ... SET last_seen_at/ip WHERE jti=? AND status=ACTIVE`. **P1-8:** if a revocation/expiry committed concurrently, status != ACTIVE → 0 rows match → a benign heartbeat can never rewrite `status=ACTIVE` / un-revoke. `clearAutomatically/flushAutomatically=true` keep the persistence context consistent. |
| `int revokeIfNotRevoked(jti, revokedAt, reason, revoked)` | Guarded `UPDATE ... SET status=REVOKED,revoked_at,revoke_reason WHERE jti=? AND status<>REVOKED`. Atomic, idempotent revocation; returns 0 if already revoked (race lost). **P1-8.** |
| `int markExpiredPastDue(cutoff, active, expired)` | Set-based `UPDATE ... SET status=EXPIRED WHERE status=ACTIVE AND expires_at<=?`. **P2:** one statement, so concurrent sweeps cannot double-transition. |
| `int claimExpiringWarning(jti, at)` | `UPDATE ... SET expiring_warned_at=? WHERE jti=? AND expiring_warned_at IS NULL`. Returns 1 if *this* caller claimed the warning, 0 if another sweep beat it. **P2 durable dedup** that replaced a racy count-the-outbox-rows heuristic. |
| `findExpiringNotYetWarned(status, after, before)` | `SELECT ... WHERE status=ACTIVE AND after<expires_at<=before AND expiring_warned_at IS NULL`. Candidate set for warnings. |

**Gotcha for new engineers:** never "fix" a status by loading the entity and `save()`-ing it in these hot paths — that reintroduces the lost-update bug. Use the guarded UPDATEs. The `@Version` column on the entity is the backstop if someone does.

#### `LicenseArtifactRepository.java`

Trivial: `JpaRepository<LicenseArtifact, String>` (PK is the `jti` string) with one method, `Optional<LicenseArtifact> findByJti(String)`. Used by `LicenseIssuer` (save) and `LicenseController.download` (pure read). No write path beyond the issue-time save.

#### `LicenseActivationRepository.java`

`JpaRepository<LicenseActivation, UUID>`. Methods:
- `findByJtiAndNodeId(jti, nodeId)` — the upsert lookup / race-recovery read.
- `findByJtiOrderByLastSeenAtDesc(jti)` — all activations (history view).
- `long countByJtiAndLastSeenAtAfter(jti, threshold)` — **active-seat count** (distinct nodes seen within the lease window). Note this counts *rows*; correctness depends on the `(jti, node_id)` uniqueness so a node = exactly one row.
- `findByJtiAndLastSeenAtAfterOrderByLastSeenAtDesc(jti, threshold)` — the active-only list.

---

### Group 4 — Controllers (HTTP surface)

#### `LicenseController.java`  — base path `/api/v1`

**Responsibility.** The operator/customer-facing REST surface for issuing, downloading, revoking, listing, and fetching licenses. Every method is guarded by a method-level `@PreAuthorize` SpEL expression delegating to the `@tenantAccess` bean (`TenantAccessChecker`) so a caller can only act within their own tenant.

| Endpoint | Auth (`@PreAuthorize`) | Behavior |
|---|---|---|
| `POST /subscriptions/{subId}/licenses` | `@tenantAccess.canIssueLicenseForSubscription(#subId)` | Issues a standard or (if `body.trial==true`) trial license. Returns `201` with `{jti, kid, issuedAt, expiresAt, license (raw JWT), downloadUrl}`. |
| `GET /licenses/{jti}/download` | `@tenantAccess.canReadLicenseByJti(#jti)` | **Pure read** of the stored artifact → `.lic` JSON attachment. |
| `POST /licenses/{jti}/revoke` | `@tenantAccess.canRevokeLicenseByJti(#jti) or hasAuthority('SUPER_ADMIN')` | Revokes; `204`. |
| `GET /licenses` | `@tenantAccess.canReadSubscription(#subscriptionId)` | Lists licenses for a **required** `subscriptionId` (paged). |
| `GET /licenses/{jti}` | `@tenantAccess.canReadLicenseByJti(#jti)` | Single license + live active-seat count. |

**Request/response records:** `IssueRequest(Integer ttlDays, List<String> audience, String notes, Boolean trial)`, `RevokeRequest(String reason)`.

**`issue` flow:** reads `ttl`/`audience`/`trial` from the optional body (all null-safe if no body), dispatches to `issuer.issueTrial` or `issuer.issue`, and returns the raw JWT inline **plus** a `downloadUrl`. Returning the JWT inline lets a caller persist the exact license immediately without a second round-trip or depending on any cache.

**`download` flow (security-critical, P1-4):**
1. `tokenRepo.findByJti(jti)` → 404 if unknown.
2. If `status == REVOKED` → **`410 Gone`** ("License is revoked"). The resource existed but is no longer downloadable.
3. `artifactRepo.findByJti(jti)` → `410 Gone` if no artifact (shouldn't happen for a normally-issued license; defensive).
4. `artifact.toIssuedLicense()` → `fileBuilder.buildEnvelopeBytes(issued, null)` + `suggestedFilename`.
5. Returns `application/json` with `Content-Disposition: attachment; filename="<slug>-<shortjti>.lic"`, status 200.
   The big comment block hammers the point: a GET must never have write side-effects — `issuer.issue()` is **never** called here.

**`list` flow:** `subscriptionId` is **mandatory** — an unscoped cross-org enumeration would be a tenant leak (and `@tenantAccess.canReadSubscription` authorizes the caller against the owning org). Optional `status` is parsed case-insensitively (`valueOf(status.toUpperCase())`), bad value → `400`. Paging via `PageRequestParams.of(page, size, null)` which enforces a server-side `MAX_SIZE` cap (P3 — a single subscription can't return an unbounded list). Ordering is fixed in the query (`issuedAt desc`).

**`revoke` flow:** resolves the acting user from `SecurityUtils.currentUser()` (nullable), delegates to `revocationService.markRevoked(jti, reason, actor)`, returns `204`.

**`getOne` flow:** returns `LicenseDto.from(token, activationService.activeSeatCount(jti))` — the only DTO variant that surfaces the live seat count.

**Gotcha:** the difference between revoked (→`410` on download) vs. expired (no special branch here — an expired token still has its artifact and would download; the *verifier* rejects it on `exp`). The download endpoint only short-circuits on REVOKED because revocation is the out-of-band kill that the JWT itself cannot express.

#### `LicenseHeartbeatController.java`  — base path `/api/v1`

**Responsibility.** The license **phone-home** surface: lets a licensed node report liveness (and thereby hold a seat), and lets operators view activations.

| Endpoint | Auth | Behavior |
|---|---|---|
| `POST /licenses/{jti}/heartbeat` | `(hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#jti)) or @tenantAccess.canReadLicenseByJti(#jti)` | Records a heartbeat for `(jti, nodeId)`, returns lease/seat state. |
| `GET /licenses/{jti}/activations` | `@tenantAccess.canReadLicenseByJti(#jti)` | Lists all node activations + active count. |

**Auth model (why two OR'd branches):** the heartbeat is meant to be called by the *licensed app itself* using an org-scoped API key carrying the `usage.ingest` scope — `canIngestUsageForJti` resolves `jti → subscription → org` and requires the caller's bound org to match (no cross-tenant heartbeat). The second branch lets a human org member with read access also beat (operator/console use). Both resolve the license's owning org, so cross-tenant heartbeats are impossible.

**`heartbeat` flow:**
1. `proxyResolver.resolveClientIp(request)` — extracts the *real* client IP honoring trusted proxies (so `last_seen_ip` isn't just the load balancer).
2. `activationService.heartbeat(jti, body.nodeId(), clientIp)` → `HeartbeatResult`.
3. Computes `overLimit = seatLimit != null && >0 && activeSeats > seatLimit` (informational flag in the response — note the *service* already rejects a *new* over-limit node with 409; this flag covers the edge where existing seats nominally exceed a since-lowered limit).
4. Returns `200` `HeartbeatResponse(jti, nodeId, lastSeenAt, activeSeats, seatLimit, overLimit, licenseType, expiresAt)`.

**Records:** `HeartbeatRequest(@NotBlank String nodeId)` (Bean Validation enforces non-blank → `400`), `HeartbeatResponse(...)`, `ActivationsResponse(jti, activeSeats, activations)`, `ActivationDto(nodeId, firstSeenAt, lastSeenAt, lastSeenIp)` with a static `from(LicenseActivation)`.

**Gotcha:** the heartbeat is *advisory* — it has zero effect on whether the offline verifier accepts the license. Its job is server-side seat accounting and detecting suspended subscriptions, not gating the customer's app.

#### `CrlController.java`  — base path `/api/v1/licenses`

**Responsibility.** Serve the **signed CRL** (the only revocation channel the offline verifier has) and a plaintext `/revoked` JSON view. This is a **public, unauthenticated** endpoint, which is exactly why it is the package's most DoS-sensitive code.

| Endpoint | Auth | Returns |
|---|---|---|
| `GET /licenses/crl` (`produces application/jwt`) | `permitAll()` | A compact JWS (`typ=crl+jwt`) listing currently-revoked, unexpired jtis. |
| `GET /licenses/revoked?since=<ISO datetime>` | `permitAll()` | Plain JSON `{revokedSince, items:[{jti, revokedAt, reason}], generatedAt}`. |

**Config.** `@Value("${app.signing.issuer}")`, `@Value("${app.signing.crl-ttl:PT1H}")` (the CRL freshness window).

**The caching machinery (audit P2 — the headline fix):**
The endpoint is `permitAll`, and previously it re-scanned the revoked set, decrypted the signing key, and Ed25519-signed **on every request** — an anonymous DoS amplification vector (cheap HTTP GET → expensive crypto). Now:

- A single `AtomicReference<CachedCrl>` holds an immutable `CachedCrl(stateKey, signedAt, jws)` snapshot, swapped atomically so concurrent readers always see a consistent value.
- On each `crl()` request: get `stateKey = revocationService.revocationStateKey()` (the cheap `count:maxRevokedAtMillis` key). Re-sign (`regenerate`) **only if** the cache is null, the `stateKey` changed (revoked set mutated), or the cached JWS is older than `crlTtl` (so `iat`/`nextUpdate` stay fresh). Otherwise serve the cached JWS.
- Response carries `Cache-Control: public, max-age=<crlTtl seconds>` so downstream caches/CDNs/verifiers also back off.

**`regenerate(stateKey, now)`:**
1. `revocationService.listActiveRevocations(now)` — **only still-unexpired revoked jtis** (P3: expired-revoked jtis are pruned because the verifier already rejects them on `exp`, so the CRL can't grow unbounded).
2. Build claims `{iss, iat (epoch secs), nextUpdate (now+crlTtl epoch secs), revoked:[jti,...]}`.
3. `jwsSigner.sign(claims, "crl+jwt")` — same active Ed25519 key, so the CRL verifies against the *same* JWKS as licenses.
4. Cache and return the fresh snapshot.

**`invalidateCache()`** — sets the cache to `null` so the next request re-signs. Exists for **key rotation**: a rotated/compromised signing key would otherwise leave the cached JWS signed under the old `kid` until TTL expiry. The keys subsystem can wire a rotation callback to invoke this; it's idempotent and safe to call defensively.

**`revoked(since)`** — a human/debug-friendly plaintext list (not signed). `since` is an optional ISO-8601 datetime (`@DateTimeFormat(iso = DATE_TIME)`); null returns all revocations. Delegates to `revocationService.listRevokedSince(since)`.

**Gotcha:** the cache is per-instance (an `AtomicReference`, not shared). In a multi-instance deployment each instance maintains its own cache; that's fine for a CRL because the `stateKey` self-heals divergence within one TTL and the content is idempotent. But it does mean `invalidateCache()` only clears *this* instance — a cluster-wide rotation needs every instance notified (or just relies on the TTL).

---

### Group 5 — Activation / seat-counting service

#### `ActivationService.java`

**Responsibility.** The transactional brain behind the heartbeat: refresh token last-seen, upsert the per-node activation, **enforce seat limits**, and report lease/seat state. Also exposes read-only seat queries used by other controllers.

**Config.** `@Value("${app.licensing.lease-window:PT24H}")` — a node not seen within this window releases its seat.

**Collaborators.** `LicenseTokenRepository` (pessimistic lock + guarded touch), `LicenseActivationRepository` (upsert/count), `SubscriptionRepository` (status + seat resolution), `PlanService` (plan-level `seats` feature). Called by `LicenseHeartbeatController` and (read-only methods) `LicenseController`.

**`HeartbeatResult heartbeat(String jti, String nodeId, String clientIp)` — `@Transactional`, the crown jewel:**

```
nodeId blank?                       → 400 badRequest
findByJtiForUpdate(jti)  ── SELECT ... FOR UPDATE (P1-9 lock held whole tx)
   not found?                       → 404 notFound
status == REVOKED?                  → 400 "License is revoked"
status == EXPIRED || exp <= now?    → 400 "License is expired"
subscription missing?               → 404
subscription status != ACTIVE?      → 409 conflict (P1-5: suspended cust. can't hold seats)
existing activation for (jti,node)?
   NO  → resolve seat limit; if active-seat count >= limit → 409 over_limit (audited)
         else INSERT activation (saveAndFlush)
              on DataIntegrityViolationException (race): re-read & UPDATE last-seen (idempotent renew)
   YES → UPDATE last_seen_at/ip on the existing row
touchLastSeenIfActive(jti, now, ip, ACTIVE)   ── guarded UPDATE (P1-8)
recount active seats; audit "license.heartbeat"
return HeartbeatResult(jti, node, now, activeSeats, seatLimit, type, expiresAt)
```

**Why each guard exists:**
- **Pessimistic lock (P1-9):** the seat-limit check is a classic TOCTOU — read count, then insert. Without serialization, N nodes could each read `limit-1` and all insert. `findByJtiForUpdate` holds a row lock on the token for the whole transaction, forcing first-beats for the same jti to queue.
- **Subscription-not-ACTIVE → 409 (P1-5):** the CRL is the only *offline* enforcement, but a suspended customer's app could keep beating and renewing leases. Rejecting the heartbeat means a suspended customer stops holding seats immediately, even before the CRL propagates.
- **Race fallback on insert:** even with the row lock, a belt-and-suspenders catch of `DataIntegrityViolationException` (unique `(jti, node_id)`) downgrades a duplicate-insert race to an idempotent renew — never a phantom extra seat.
- **`touchLastSeenIfActive` (P1-8):** the final last-seen refresh is a *guarded* UPDATE that matches only ACTIVE tokens, not a full-entity `save()`. If a revocation/expiry committed during this transaction, status != ACTIVE so it matches 0 rows and cannot resurrect the token.

**Seat enforcement nuance:** a *new* node is rejected at `activeSeats >= seatLimit` (audited as `license.activation.over_limit`). An *already-known* node always succeeds (renews its lease, never grows the count). `null`/`<=0` seat limit = unlimited.

**Read-only helpers (`@Transactional(readOnly = true)`):**
- `long activeSeatCount(jti)` — count of activations within the lease window (used by `LicenseController.getOne` and the activations endpoint).
- `List<LicenseActivation> listActivations(jti)` — full history, most-recent first.
- `List<LicenseActivation> listActiveActivations(jti)` — only those within the lease window.
- `Duration getLeaseWindow()` — exposes the configured window.

**`Integer resolveSeatLimit(Subscription sub)`** (package-private): precedence is **explicit `subscriptions.seats` column** (when `> 0`) → **plan feature `seats`** (numeric, or numeric string) → **null (unlimited)**. Any `RuntimeException` while reading the plan feature is swallowed to "unlimited" — a misconfigured/non-numeric `seats` feature must never block legitimate heartbeats.

**`record HeartbeatResult(jti, nodeId, lastSeenAt, activeSeats, seatLimit, licenseType, expiresAt)`** — the controller maps this to the wire response.

**Gotcha:** `resolveSeatLimit` is computed twice in `heartbeat` (once for the new-node gate, once for the result) — cheap, and the second read reflects the same locked subscription.

---

### Group 6 — Revocation, lifecycle, DTO

#### `LicenseRevocationService.java`

**Responsibility.** The transactional service that flips licenses to `REVOKED`, cascades revocation across a subscription, and feeds the CRL its data + cache key. Idempotent and race-safe by design.

**Collaborators.** `LicenseTokenRepository` (guarded revoke + revocation queries), `OutboxPublisher`. Called by `LicenseController.revoke`, `CrlController` (read paths), and (cascade) by subscription suspend/cancel flows.

| Method | Behavior |
|---|---|
| `LicenseToken markRevoked(jti, reason, actorUserId)` — `@Transactional` | Loads token (404 if missing); if already REVOKED, returns as-is (idempotent). Else `revokeIfNotRevoked(...)` guarded UPDATE; if it updated 0 rows (lost the race to another revoker) re-reads and returns. On success: sets `AuditContext` (`license.revoked`, reason, `revoked_by`), publishes `LicenseRevoked` outbox event, returns the refreshed token. |
| `int revokeAllActiveForSubscription(subId, reason, actorUserId)` — `@Transactional` | **P1-5 cascade.** Loads all ACTIVE tokens for the subscription and revokes each via `markRevoked` (so each gets its own audit + outbox event). Returns count. Called when a subscription is suspended/cancelled so the now-invalid offline licenses reach the CRL rather than staying seat-holding-valid until natural expiry. |
| `List<LicenseToken> listRevokedSince(since)` — read-only | All REVOKED ordered by `revokedAt asc`, optionally filtered to after `since`. Backs `GET /revoked`. |
| `List<LicenseToken> listActiveRevocations(now)` — read-only | REVOKED **and still unexpired** as of `now` — the only jtis the signed CRL carries (P3 prune). Backs `CrlController.regenerate`. |
| `String revocationStateKey()` — read-only | `"<count>:<maxRevokedAtMillis>"`. Cheap CRL cache key (P2): changes whenever a token is revoked, so `CrlController` re-signs only on real change. |

**Why guarded UPDATE for revocation (P1-8):** revocation must win against a concurrent heartbeat's last-seen update; doing it as `UPDATE ... WHERE status<>REVOKED` is atomic and means the jti reliably reaches the CRL and can never be un-revoked.

**Gotcha:** `markRevoked` returning the existing token on an already-revoked / race-lost path keeps the operation idempotent — calling revoke twice is a no-op, not an error (the controller still returns `204`).

#### `LicenseLifecycleScheduler.java`

**Responsibility.** A `@Scheduled` background sweeper that (a) transitions overdue ACTIVE tokens to EXPIRED and emits `license.expired`, and (b) emits a one-time `license.expiring` warning for tokens nearing expiry. Keeps the server-side status column and notification stream in sync with the truth the verifier already enforces via `exp`.

**Config.** `@Value("${app.licensing.expiry-warning:P14D}")` (warning lead time); schedule via `${app.licensing.lifecycle.fixed-delay:PT5M}` + `${app.licensing.lifecycle.initial-delay:PT1M}`.

**Collaborators.** `LicenseTokenRepository`, `SubscriptionRepository` (to enrich event payloads with `org_id`), `OutboxPublisher`, and a `@Lazy` self-reference.

**The `@Lazy` self-injection (audit P2 proxy-bypass) — important pattern:**
```java
private final LicenseLifecycleScheduler self;   // injected @Lazy to break the construction cycle
```
The `@Scheduled` method `sweep()` calls `self.expirePastDue()` / `self.warnExpiring()` **through the Spring proxy**, not via plain `this.`. A plain self-invocation would bypass the `@Transactional` advice, so the status UPDATE and its outbox inserts would not commit atomically. `@Lazy` breaks the self-referential bean cycle at construction time.

**`sweep()`** — the scheduled entry point: routes through `self` for transactional correctness, logs a single line only when there was work, and catches `RuntimeException` so a transient failure doesn't kill the scheduler thread.

**`int expirePastDue()` — `@Transactional`:**
1. Snapshot overdue tokens: `findByStatusAndExpiresAtLessThanEqual(ACTIVE, now)` — needed *before* the UPDATE to build per-token event payloads.
2. Empty? return 0.
3. `markExpiredPastDue(now, ACTIVE, EXPIRED)` — one guarded set-based UPDATE; concurrent sweeps can't double-transition.
4. For each snapshotted token, publish a `license.expired` event. Transition + events commit atomically.

**`int warnExpiring()` — `@Transactional`:**
1. `findExpiringNotYetWarned(ACTIVE, now, now+expiryWarning)` — ACTIVE, within the window, `expiring_warned_at IS NULL`.
2. For each, `claimExpiringWarning(jti, now)` — the **durable dedup**: only the call that flips NULL→now (returns 1) emits the `license.expiring` event; a `0` (another sweep already claimed it) is skipped. This survives outbox purges and concurrent/duplicate sweeps. Does **not** change token status.

**`eventPayload(token, now)`** — builds `{jti, subscription_id, org_id (looked up), license_type, expires_at, evaluated_at}` for both event types.

**Gotcha:** `expirePastDue` snapshots *then* updates — there's a tiny window where a token in the snapshot could be concurrently revoked; but the set-based UPDATE only matches `status=ACTIVE`, so a concurrently-revoked token simply isn't transitioned (it stays REVOKED), while it may still get a stale `license.expired` event from the pre-fetched snapshot. In practice expiry vs. revocation are both terminal and the event is informational, so this is acceptable; just be aware the snapshot and the UPDATE are not perfectly atomic with respect to *other* status changes.

#### `LicenseDto.java`

**Responsibility.** The read-model record returned by `GET /licenses` and `GET /licenses/{jti}`. Flattens a `LicenseToken` into a JSON-friendly shape and optionally carries the live active-seat count.

**Shape:** `record LicenseDto(id, jti, subscriptionId, kid, issuedAt, expiresAt, revokedAt, revokeReason, fingerprint, lastSeenAt, lastSeenIp, status, licenseType, activeSeats)`.

**Factories:**
- `static LicenseDto from(LicenseToken t)` — `activeSeats = null` (list view; computing per-row seat counts would be N+1).
- `static LicenseDto from(LicenseToken t, Long activeSeats)` — the detail variant (used by `getOne`).

**Gotchas:** `status` and `licenseType` are emitted as `String` (`.name()`), not the enums — a stable wire contract decoupled from the Java enum. `licenseType` is null-guarded (`t.getLicenseType() == null ? null : ...`) for defensiveness even though the entity defaults it to `STANDARD`. Notably the DTO **does not** expose the raw JWT — that only comes back from `issue` (inline) and `/download` (envelope), keeping list/detail reads lightweight and avoiding leaking signed material in bulk listings.

---

## Cross-cutting concerns & invariants (cheat-sheet)

| Concern | Where | Mechanism |
|---|---|---|
| Offline verification | whole package | Ed25519 JWS (`license+jwt`), verified against JWKS; no callback. |
| Pure-read downloads (P1-4) | `LicenseArtifact`, `LicenseController.download` | Signed bytes persisted at issue; GET never re-mints. |
| Lost-update protection (P1-8) | `LicenseToken.@Version`, `touchLastSeenIfActive`, `revokeIfNotRevoked` | Guarded conditional UPDATEs + optimistic lock. |
| Seat TOCTOU (P1-9) | `findByJtiForUpdate`, `ActivationService.heartbeat` | Pessimistic row lock around count+insert, with a unique-constraint race fallback. |
| Suspended-customer enforcement (P1-5) | `ActivationService` (409), `revokeAllActiveForSubscription` (cascade) | Reject heartbeats + cascade revocation to the CRL. |
| CRL signing DoS (P2) | `CrlController` cache + `revocationStateKey` | Cache signed JWS; re-sign only on revoked-set change or TTL. |
| Atomic expiry/warn (P2) | `LicenseLifecycleScheduler` + `@Lazy self` | Proxy-routed `@Transactional`; set-based UPDATE; durable warn dedup. |
| Unbounded growth / overflow (P3) | `clampTtlDays`, `listActiveRevocations`, `PageRequestParams` cap | Clamp TTL; prune expired revocations from CRL; cap page size. |
| Tenant isolation | every controller method | `@PreAuthorize` → `@tenantAccess` (`TenantAccessChecker`) resolving jti/sub → org. |
| Real client IP | `LicenseHeartbeatController` | `TrustedProxyResolver.resolveClientIp`. |
| Auditing & events | `LicenseIssuer`, `LicenseRevocationService`, `ActivationService`, scheduler | `AuditContext` + transactional `OutboxPublisher`. |
