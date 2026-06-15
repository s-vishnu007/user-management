# Package `com.example.cp.subscriptions`

## Module overview

This package is the **subscription domain** of the control-panel API. A *subscription* binds one **organization** (the customer tenant) to one **plan** (the catalog SKU that carries the baseline permissions/features and a default TTL) for a fixed time window, and tracks its **lifecycle status** (`ACTIVE`, `SUSPENDED`, `EXPIRED`, `CANCELLED`). It is the pivot of the whole licensing flow: a license (`.lic` JWT) is only ever minted *for a subscription*, the JWT's embedded `permissions`/`features` are computed from *plan baseline + this subscription's overrides*, and the JWT's expiry is bounded by the subscription's `ends_at`. The package owns three concerns the rest of the audit history keeps coming back to:

1. **Lifecycle / state machine** — a strict, table-driven set of allowed status transitions where `CANCELLED` and `EXPIRED` are terminal (P2 hardening, so a cancelled customer can't be resurrected through `suspend -> reactivate`).
2. **Overrides** — per-subscription deltas on top of the plan: grant/revoke a single permission, or set/override a single feature value. These are upserted on a `(subscription, type, key)` unique key (P3) so entitlement resolution is deterministic, and merged in deterministic order by `resolveEntitlements(...)` for the license issuer.
3. **Cascade-revoke-to-CRL** — suspending or cancelling a subscription doesn't just flip a status column; it *cascades revocation* to every still-ACTIVE license of that subscription so those offline `.lic` tokens land on the signed CRL (Certificate Revocation List) instead of remaining valid (and seat-holding) until natural expiry (P1-5).

The package contains 8 source files: two JPA entities (`Subscription`, `SubscriptionOverride`), their two Spring-Data repositories, one read DTO (`SubscriptionDto`), the orchestrating `SubscriptionService`, the REST surface `SubscriptionController`, and a small transactional-outbox helper `OutboxPublisher` that — although it physically lives in this package — is shared by the licensing and key-rotation services too.

### How it fits the bigger picture

```
                 (writes)                         (reads + cascades)
HTTP ─► SubscriptionController ─► SubscriptionService ──┬─► SubscriptionRepository ─► subscriptions
        @PreAuthorize(@tenantAccess…)                   ├─► SubscriptionOverrideRepository ─► subscription_overrides
                                                         ├─► OrganizationRepository / PlanRepository (validation)
                                                         ├─► OutboxPublisher ─► outbox_events (domain events)
                                                         └─► LicenseRevocationService.revokeAllActiveForSubscription(…)
                                                                  on suspend()/cancel() → licenses → CRL

LicenseIssuer.issue(subId) ─► SubscriptionService.get / resolveEntitlements ─► LicenseClaimsBuilder ─► signed .lic JWT
LicenseLifecycleScheduler.expirePastDue() ─► (sets LICENSE tokens EXPIRED; SubscriptionRepository used only for org_id lookup)
TenantAccessChecker.resolveOrgForSubscription(subId) ─► SubscriptionRepository (authorization of *every* subscription/license/usage endpoint)
```

Two consumers outside this package depend heavily on it:

- **`com.example.cp.licenses`** — `LicenseIssuer` refuses to mint a license unless `subscription.status == ACTIVE`, then calls `SubscriptionService.resolveEntitlements(...)` (via `LicenseClaimsBuilder`) to fill the JWT's `permissions`/`features`. `LicenseRevocationService` is called *back into* by this package's `suspend`/`cancel`.
- **`com.example.cp.security.TenantAccessChecker`** — the central `@PreAuthorize` SpEL bean resolves the owning org of *any* subscription/license/usage target by reading `SubscriptionRepository`, so this package's data shape is load-bearing for the entire authorization model.

> Note on `EXPIRED`: nothing in *this* package ever writes `EXPIRED` to a subscription. The enum value exists and is recognized as a **terminal** state by the state machine, but the only sweeper that exists today (`LicenseLifecycleScheduler`) expires **license tokens**, not subscriptions — see the gotcha at the end of `SubscriptionService`.

---

## File: `Subscription.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/Subscription.java`

**Responsibility.** The JPA `@Entity` mapped to table `subscriptions`. It is the aggregate root of the package: one row per customer subscription, carrying its plan binding, validity window, seat count, free-text notes, and lifecycle status.

### `public class Subscription`

Lombok-generated: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. The builder is used by `SubscriptionService.createSubscription`; the no-args constructor + setters are required by Hibernate.

#### Nested enum `Status { ACTIVE, SUSPENDED, EXPIRED, CANCELLED }`

Persisted as `EnumType.STRING` (so the DB stores the literal text, matching the `CHECK (status IN (...))` constraint in migration 04 — see below). Storing as STRING rather than ORDINAL is deliberate: reordering or inserting enum constants later won't silently re-map existing rows.

| Status | Meaning | Terminal? |
|---|---|---|
| `ACTIVE` | Live; licenses may be issued | No |
| `SUSPENDED` | Temporarily disabled; licenses cascade-revoked; can be reactivated | No |
| `EXPIRED` | Past `ends_at` (reserved; not currently set by code) | **Yes** |
| `CANCELLED` | Permanently terminated; licenses cascade-revoked | **Yes** |

#### Fields / columns

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | Assigned application-side via `Ids.newId()` (UUIDv7) — *not* DB-generated, even though migration declares a `gen_random_uuid()` default. |
| `long version` | `version` | `@Version` optimistic-lock counter. Incremented on every update; a concurrent update that writes a stale version throws `OptimisticLockException` (→ surfaced as a 409/500 by the global handler). This is the P1 concurrency guard so two admins flipping the same subscription can't silently clobber each other. |
| `UUID orgId` | `org_id` (NOT NULL) | Owning tenant. **The authorization anchor** — `TenantAccessChecker` reads this to decide who may touch the subscription. |
| `UUID planId` | `plan_id` (NOT NULL) | The plan whose baseline permissions/features/TTL apply. |
| `Status status` | `status` (NOT NULL, STRING) | Lifecycle state. |
| `OffsetDateTime startsAt` | `starts_at` (NOT NULL) | Validity window start. |
| `OffsetDateTime endsAt` | `ends_at` (NOT NULL) | Validity window end; caps license expiry in `LicenseClaimsBuilder`. |
| `Integer seats` | `seats` (nullable) | Seat allowance; copied into the license `seats` claim. Nullable → treated as `0` in the activation outbox payload. |
| `String notes` | `notes` (nullable) | Free-text admin note. |
| `UUID createdBy` | `created_by` (nullable) | Actor user id at creation; resolved from `SecurityUtils.currentUser()` (null for api-key/system actors). |
| `OffsetDateTime createdAt` | `created_at` (NOT NULL) | Set application-side at creation. |

**Collaborators.** Built by `SubscriptionService`; read by `TenantAccessChecker`, `LicenseIssuer`, `LicenseClaimsBuilder`, and `LicenseLifecycleScheduler` (org-id lookup for event payloads).

**Gotcha.** `id` and `createdAt` are populated by application code, not the DB defaults. Because `@Id` is set before save and there's no `@GeneratedValue`, Hibernate treats a fresh entity as *detached/merge* in some flows — here `subRepo.save(...)` works because the entity is new and unmanaged. If you ever add a code path that re-saves a `Subscription` you constructed by hand (not loaded), make sure `version` is set correctly or you'll trip the optimistic lock.

---

## File: `SubscriptionOverride.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionOverride.java`

**Responsibility.** JPA `@Entity` for table `subscription_overrides` — a per-subscription delta applied on top of the plan baseline when computing license entitlements.

### `public class SubscriptionOverride`

Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.

#### Nested enum `Type { PERMISSION, FEATURE }`

- `PERMISSION` — grant or revoke a single permission string (the `key`). Whether it grants or revokes is decided by the *truthiness* of `value_json` (see `SubscriptionService.isTruthy`).
- `FEATURE` — set/override a single feature flag/limit; the `key` is the feature name and `value_json` is its (arbitrary JSON) value, which overwrites the plan's value for that key.

#### Fields / columns

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | App-assigned `Ids.newId()`. |
| `UUID subscriptionId` | `subscription_id` (NOT NULL) | FK to `subscriptions(id)`, `ON DELETE CASCADE` in the DB. Note this is a raw `UUID`, **not** a JPA `@ManyToOne` relation — the package models the FK loosely and resolves the parent via the service, avoiding lazy-loading surprises. |
| `Type type` | `type` (NOT NULL, STRING) | |
| `String key` | `key` (NOT NULL) | Permission name or feature name. |
| `String valueJson` | `value_json` (jsonb) | `@JdbcTypeCode(SqlTypes.JSON)` so Hibernate binds it as Postgres `jsonb`. Stored as a JSON *string* in Java; serialized/parsed by `SubscriptionService` via Jackson. Nullable. |

**Unique key.** `(subscription_id, type, key)` is `UNIQUE` (migration 18). This is the linchpin that makes `resolveEntitlements` deterministic — at most one override per key, so resolution order can't change the result.

**Collaborators.** Created/updated/deleted by `SubscriptionService.persistOverride/removeOverride`; read in bulk by `toDto` and `resolveEntitlements`.

**Gotcha.** `valueJson` being `String` + `@JdbcTypeCode(JSON)` means you must hand it valid JSON text. `SubscriptionService` always produces it with `objectMapper.writeValueAsString(...)`, so a string value `"foo"` is stored as `"foo"` (quoted JSON), a boolean as `true`, etc. Reading it back goes through `parseValue`, which falls back to returning the *raw* string if it can't be parsed — so a manually-corrupted row degrades gracefully rather than 500-ing.

---

## File: `SubscriptionRepository.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionRepository.java`

**Responsibility.** Spring-Data `JpaRepository<Subscription, UUID>` — CRUD + a couple of derived finders.

### `public interface SubscriptionRepository`

| Method | What it does | Why it exists / who calls it |
|---|---|---|
| (inherited) `findById`, `save`, `delete`, … | Standard CRUD | `SubscriptionService.get/create/suspend/...`; `TenantAccessChecker.resolveOrgForSubscription`; `LicenseClaimsBuilder`/`LicenseLifecycleScheduler` org lookups. |
| `List<Subscription> findByOrgId(UUID orgId)` | All subscriptions of an org | `SubscriptionService.listByOrg` → `GET /orgs/{orgId}/subscriptions`. |
| `List<Subscription> findByOrgIdAndStatus(UUID, Status)` | Filtered by status | Provided for callers that need e.g. only ACTIVE subscriptions; available to billing/reporting code. |

**Gotcha.** `findById` here is the single most-called method in the authorization hot path — `TenantAccessChecker` calls `resolveOrgForSubscription` on *every* `@PreAuthorize` for subscription/license/usage endpoints. There is an index on `subscriptions(org_id)` but the PK lookup is what matters; it's a primary-key hit so it's cheap, but be aware authorization adds one extra `SELECT` per protected request.

---

## File: `SubscriptionOverrideRepository.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionOverrideRepository.java`

**Responsibility.** Spring-Data repository for overrides.

### `public interface SubscriptionOverrideRepository`

| Method | What it does | Why it exists |
|---|---|---|
| `List<SubscriptionOverride> findBySubscriptionId(UUID)` | All overrides for a subscription (unordered) | Used by `toDto` and `listOverrides` (presentation; order not load-bearing there). |
| `List<SubscriptionOverride> findBySubscriptionIdOrderByTypeAscKeyAsc(UUID)` | Overrides in deterministic `(type, key)` order | Used by `resolveEntitlements` so the merged result is **independent of row insertion order** (P3). Pairs with the unique constraint to guarantee reproducible license claims. |
| `Optional<SubscriptionOverride> findBySubscriptionIdAndTypeAndKey(UUID, Type, String)` | Single override by natural key | The **upsert lookup** in `persistOverride` — find-then-update-or-insert on the `(subscription, type, key)` unique key. |
| `void deleteBySubscriptionId(UUID)` | Bulk delete all overrides for a subscription | Convenience cleanup (DB also cascades on subscription delete). Not currently called from this package's service but available for teardown. |

**Gotcha.** The unordered `findBySubscriptionId` and the ordered variant return the *same rows*; the ordered one exists purely because resolution must be deterministic. If you ever route `resolveEntitlements` through the unordered finder, you reintroduce the P3 order-dependence bug.

---

## File: `SubscriptionDto.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionDto.java`

**Responsibility.** The read-model returned by the REST API. A `record` so it's immutable and serializes cleanly to JSON. It flattens the entity plus a resolved `planCode` (joined from the plan) and the subscription's overrides.

### `public record SubscriptionDto(...)`

Fields mirror the entity (`id, orgId, planId, status, startsAt, endsAt, seats, notes, createdBy, createdAt`) **plus**:
- `String planCode` — the human-readable plan code, looked up by `toDto`; `null` if the plan no longer exists (defensive — a dangling `plan_id` won't 500 the listing).
- `String status` — the enum rendered as its `.name()` (string, not the Java enum), keeping the wire contract stable.
- `List<OverrideDto> overrides` — the subscription's overrides with their JSON values *parsed back into objects* (so the client gets `true`/`42`/`"x"`, not a JSON string).

#### `public record OverrideDto(UUID id, String type, String key, Object value)`

The `value` is `Object` because an override value can be any JSON shape (boolean, number, string, object). It's produced either by `parseValue(valueJson)` (in `toDto`) or echoed straight back from the request (in `addOverride`).

**Collaborators.** Built exclusively by `SubscriptionService.toDto`; returned by every `SubscriptionController` endpoint except the deletes.

**Gotcha.** There's a subtle inconsistency between the two ways an `OverrideDto` is constructed: `toDto` round-trips the value through Jackson (`parseValue`), while `SubscriptionController.addOverride` echoes the *original request value object* without round-tripping. For most values they're identical, but if a client POSTs a value Jackson would normalize (e.g. a large integer or a date string), the immediate POST response and a later GET could differ in representation.

---

## File: `OutboxPublisher.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/OutboxPublisher.java`

**Responsibility.** A thin **transactional-outbox** writer: it inserts a domain-event row into `outbox_events` inside the *caller's* transaction. A separate delivery component (owned by another agent) reads the outbox and does the actual `NOTIFY`/webhook fan-out. Despite living in `subscriptions`, it's shared infrastructure.

### `@Component("subscriptionOutboxPublisher") public class OutboxPublisher`

Registered under the explicit bean name `subscriptionOutboxPublisher` — important because there may be other `OutboxPublisher`-shaped beans; the licensing and key services inject *this* one by that name (e.g. `LicenseLifecycleScheduler` constructor takes this `OutboxPublisher`).

Dependencies: a plain `JdbcTemplate` (raw SQL, no JPA) and the shared `ObjectMapper`.

#### `void publish(String aggregateType, String aggregateId, String eventType, Map<String,Object> payload)`

```java
INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json)
VALUES (?, ?, ?, ?::jsonb)
```

- Serializes `payload` to JSON (null payload → `{}`), then inserts one row.
- Uses `JdbcTemplate` rather than a JPA entity deliberately: it's a fire-and-forget insert, and using raw SQL keeps it out of the Hibernate dirty-checking/flush ordering.
- **Why it matters (the outbox pattern):** the insert runs in the *same DB transaction* as the business write that called it. So `createSubscription` either commits both the `subscriptions` row **and** the `SubscriptionActivated` event, or neither — no lost events, no phantom events. The actual network delivery happens later, after commit, by the delivery worker reading `outbox_events`.
- On any failure it wraps and rethrows as a `RuntimeException`, which will **roll back the caller's transaction**. This is intentional fail-closed behavior: if we can't record the event, we don't want to half-complete the business operation.

**Events emitted through it from this package:** `SubscriptionActivated`, `SubscriptionSuspended`, `SubscriptionReactivated`, `SubscriptionCancelled` (from `SubscriptionService`), plus `LicenseRevoked` (from the cascade in `LicenseRevocationService`) and license lifecycle events from elsewhere.

**Gotcha.** Because a publish failure rolls back the whole transaction, malformed payloads matter. Payloads here are built from `Map.of(...)` with primitive/string values, so serialization can't realistically fail — but if you ever pass a non-serializable object you'll convert a successful business operation into a 500 + rollback.

---

## File: `SubscriptionController.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionController.java`

**Responsibility.** The REST surface. `@RestController` at base path `/api/v1`. Every method is guarded by a method-level `@PreAuthorize` SpEL expression that delegates to the `@tenantAccess` bean (`TenantAccessChecker`) — *this is the entire tenant-isolation enforcement for subscriptions*. The controller is thin: validate → call service → map to DTO.

### `public class SubscriptionController`

Single dependency: `SubscriptionService subService`.

#### Endpoints

| HTTP | Path | `@PreAuthorize` | Service call | Returns |
|---|---|---|---|---|
| `GET` | `/orgs/{orgId}/subscriptions` | `@tenantAccess.canReadSubscriptionInOrg(#orgId)` → org read access | `listByOrg(orgId)` → `map(toDto)` | `List<SubscriptionDto>` |
| `POST` | `/orgs/{orgId}/subscriptions` | `@tenantAccess.canWriteSubscriptionInOrg(#orgId)` → org manage (OWNER/ADMIN) | `createSubscription(...)` | `201` + `SubscriptionDto` |
| `GET` | `/subscriptions/{id}` | `@tenantAccess.canReadSubscription(#id)` | `get(id)` → `toDto` | `SubscriptionDto` |
| `POST` | `/subscriptions/{id}/suspend` | `@tenantAccess.canWriteSubscription(#id)` | `suspend(id, reason)` | `SubscriptionDto` |
| `POST` | `/subscriptions/{id}/reactivate` | `@tenantAccess.canWriteSubscription(#id)` | `reactivate(id)` | `SubscriptionDto` |
| `POST` | `/subscriptions/{id}/cancel` | `@tenantAccess.canWriteSubscription(#id)` | `cancel(id, reason)` | `SubscriptionDto` |
| `POST` | `/subscriptions/{id}/overrides` | `@tenantAccess.canWriteSubscription(#id)` | `addOverride(id, body)` | `OverrideDto` |
| `DELETE` | `/subscriptions/{id}/overrides/{overrideId}` | `@tenantAccess.canWriteSubscription(#id)` | `removeOverride(id, overrideId)` | `204 No Content` |

**Authorization flow (how `@tenantAccess` enforces tenancy).** For the `{id}`-scoped methods, `TenantAccessChecker` resolves the subscription's `org_id` (`resolveOrgForSubscription`) and then applies the org rule:
- **Read** (`canReadSubscription` → `canAccessOrg`): super_admin always; an api-key principal only if bound to that org; a human only if they're an `OrgMember`.
- **Write** (`canWriteSubscription` → `canManageOrg`): super_admin always; **api-keys are denied writes by default**; a human only if `OWNER`/`ADMIN` rank.
- **Default-deny:** a missing subscription resolves to an empty `Optional` → `false` → 403. So requesting another tenant's (or a nonexistent) subscription id is indistinguishable from "forbidden," which avoids an existence-oracle IDOR.

**Request bodies (records defined in-file):**
- `CreateSubscriptionRequest(planId, startsAt, endsAt, seats, notes, overrides)` — `@Valid`-checked. `startsAt`/`endsAt` may be null (service fills defaults). `overrides` is an optional initial batch.
- `ReasonRequest(reason)` — optional body (`@RequestBody(required=false)`) for suspend/cancel; `body == null` → `reason = null`.
- The override POST reuses `SubscriptionService.OverrideRequest(type, key, value)` directly as its `@Valid` body.

**Gotchas.**
- The override endpoints authorize on the *subscription's* org (`canWriteSubscription(#id)`), and `removeOverride` independently verifies the override actually belongs to that subscription (in the service), so you can't delete another subscription's override by guessing its id even within the same org.
- `suspend`/`cancel` will return **409 Conflict** (not 4xx-validation) if the subscription is in a terminal state — this comes from the service's `assertTransitionAllowed`, surfaced via `ApiException.conflict`.
- All write endpoints return the *full refreshed* `SubscriptionDto` (re-derived through `toDto`), so a client always sees the post-transition status and current override list.

---

## File: `SubscriptionService.java`

`control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionService.java`

**Responsibility.** The heart of the package — the `@Service` that owns the subscription lifecycle state machine, override upsert/resolution logic, DTO mapping, audit-context population, outbox publishing, and the **cascade-revoke-to-CRL** behavior on suspend/cancel.

### `@Service public class SubscriptionService`

Dependencies (constructor-injected): `SubscriptionRepository`, `SubscriptionOverrideRepository`, `OrganizationRepository`, `PlanRepository`, `OutboxPublisher`, `LicenseRevocationService`, `ObjectMapper`.

### The state machine: `ALLOWED_TRANSITIONS` + `assertTransitionAllowed`

A `static final Map<Status, Set<Status>>` built once in a static initializer:

```
ACTIVE     -> { SUSPENDED, CANCELLED }
SUSPENDED  -> { ACTIVE, CANCELLED }
CANCELLED  -> { }      (terminal)
EXPIRED    -> { }      (terminal)
```

```
        ┌──────────── cancel ───────────┐
        ▼                                │
   ┌─────────┐  suspend   ┌───────────┐  │
   │ ACTIVE  │──────────► │ SUSPENDED │──┘
   │         │ ◄──────────│           │
   └────┬────┘ reactivate └─────┬─────┘
        │ cancel                │ cancel
        ▼                       ▼
   ┌──────────────  CANCELLED  ──────────────┐  (terminal — no way back)
   └──────────────────────────────────────────┘

   EXPIRED  (terminal; reserved — not set by this package today)
```

#### `private void assertTransitionAllowed(Status from, Status to)`

- `from == to` → **allowed** (no-op; callers treat re-suspending an already-suspended sub idempotently).
- Otherwise looks up the allowed set; if `to` isn't in it → `ApiException.conflict("Illegal subscription transition: from -> to")` → **HTTP 409**.

**Why this exists (P2).** Before the state machine, `cancel -> suspend -> reactivate` could resurrect a cancelled subscription, re-enabling a terminated customer. Making `CANCELLED`/`EXPIRED` terminal closes that. The same-status short-circuit keeps the suspend/cancel endpoints idempotent.

### Lifecycle methods

#### `@Transactional Subscription createSubscription(orgId, planId, startsAt, endsAt, seats, notes, overrides)`

Flow:
1. Load org (`orgRepo.findById` → 404 if missing) and plan (404 if missing).
2. `if (!plan.isActive()) → 400 "Plan is not active"` — you can't subscribe to a retired plan.
3. Compute window: `starts = startsAt ?? now`; `ends = endsAt ?? starts.plusDays(plan.defaultTtlDays)`. Validate `ends.isAfter(starts)` else **400** `"ends_at must be after starts_at"`.
4. Resolve actor id from `SecurityUtils.currentUser()` (null for api-key/system).
5. Build the `Subscription` (status forced to `ACTIVE`, app-assigned `id`/`createdAt`) and `save`.
6. Persist any initial `overrides` via `persistOverride` (upsert each).
7. Populate `AuditContext` (`subscription.created`, target, `org_id`, `plan_code`).
8. `outbox.publish("subscription", id, "SubscriptionActivated", {...})` with subscription/org/plan ids, window, and seats (null seats → `0`).
9. Return the saved entity.

**Notes.** All of this is one transaction, so the subscription row, its overrides, the audit payload, and the outbox event commit atomically. The status is always `ACTIVE` at birth — there's no way to create something already suspended/cancelled.

#### `@Transactional Subscription suspend(UUID id, String reason)` — *cascade-revoke*

1. `get(id)` (404 if missing).
2. `assertTransitionAllowed(status, SUSPENDED)` — 409 if terminal (CANCELLED/EXPIRED) or otherwise illegal.
3. Set `SUSPENDED`, save.
4. **Cascade (P1-5):** `revoked = revocationService.revokeAllActiveForSubscription(id, "subscription suspended[: reason]", actorId)`.
5. Audit `subscription.suspended` with `reason` and `licenses_revoked` count.
6. `outbox.publish(... "SubscriptionSuspended" ...)`.

#### `@Transactional Subscription cancel(UUID id, String reason)` — *cascade-revoke*

Identical shape to `suspend` but transitions to `CANCELLED` (terminal) and emits `SubscriptionCancelled`. Also cascades revocation. Reason is interpolated into the revocation reason string.

#### `@Transactional Subscription reactivate(UUID id)`

`get` → `assertTransitionAllowed(status, ACTIVE)` → set `ACTIVE`, save → audit `subscription.reactivated` → emit `SubscriptionReactivated`. Because `CANCELLED`/`EXPIRED` have empty allowed-sets, reactivating a terminal subscription is a **409**. **Important asymmetry:** reactivate does **not** re-issue or un-revoke any licenses — the licenses that were cascade-revoked on suspend stay revoked (they're on the CRL permanently). After reactivation you must *issue new licenses*. This is by design: a revoked jti must never become valid again.

##### The cascade in detail (suspend/cancel → CRL)

```
suspend(subId, reason)
   └─► LicenseRevocationService.revokeAllActiveForSubscription(subId, reason, actorId)
          ├─ findBySubscriptionIdAndStatus(subId, ACTIVE)   // every live license of this sub
          └─ for each token: markRevoked(jti, reason, actorId)
                 ├─ revokeIfNotRevoked(...)  // guarded conditional UPDATE, race-safe vs heartbeats
                 ├─ AuditContext (license.revoked)           // per-token audit
                 └─ outbox.publish("license_token", jti, "LicenseRevoked", {...})
                                       │
          (later) CRL controller lists REVOKED-and-unexpired jtis ─► signs an Ed25519 JWS CRL
                                       │
          customer Docker app's verifier SDK fetches the CRL ─► rejects those jtis offline
```

- All of this runs **inside the suspend/cancel transaction** (both methods are `@Transactional`, and `revokeAllActiveForSubscription` is too — same transaction since it's a nested service call on the same thread). So the status flip, every token revocation, every per-token `LicenseRevoked` event, and the `SubscriptionSuspended/Cancelled` event commit atomically. If anything fails, the subscription stays in its old state and nothing is revoked.
- `markRevoked` uses a **guarded conditional UPDATE** (`revokeIfNotRevoked`) so a concurrent license heartbeat updating `last_seen` can't race the revoke — the jti reliably reaches the CRL exactly once (P1-8). A lost race (already revoked) is a no-op, so re-suspending is safe.
- The CRL itself is pruned to *revoked-but-unexpired* jtis (offline verifiers already reject expired tokens), keeping it bounded (P3).
- **Why cascade at all:** without it, suspending a customer flipped a column but their `.lic` files kept verifying offline (and holding seats) until natural expiry — a real entitlement-leak. The cascade is the mechanism that makes suspension actually *take effect* on offline clients.

### Read methods

| Method | Tx | Returns |
|---|---|---|
| `Subscription get(UUID id)` | readOnly | the entity or **404** |
| `List<Subscription> listByOrg(UUID orgId)` | readOnly | all subs of an org |
| `List<SubscriptionOverride> listOverrides(UUID subscriptionId)` | readOnly | unordered overrides |

### Override management

#### `@Transactional SubscriptionOverride addOverride(UUID subscriptionId, OverrideRequest req)`

`get(subscriptionId)` (404 guard) → `persistOverride` (upsert) → audit `subscription.override.added`. Returns the persisted override.

#### `@Transactional void removeOverride(UUID subscriptionId, UUID overrideId)`

Loads the override (404 if missing); **verifies `ov.subscriptionId == subscriptionId`** else **400** `"Override does not belong to that subscription"` (defense against deleting another subscription's override by id); deletes; audits `subscription.override.removed`.

#### `private SubscriptionOverride persistOverride(UUID subscriptionId, OverrideRequest req)` — *the upsert*

1. Parse `req.type()` case-insensitively → `Type`; invalid → **400** `"Invalid override type: ..."`.
2. `req.key()` null/blank → **400** `"Override key is required"`.
3. Serialize `req.value()` to JSON via Jackson; failure → **400** `"Invalid override value"`.
4. **Upsert (P3):** `findBySubscriptionIdAndTypeAndKey(...)` — if a row exists, update its `valueJson`; otherwise build a new row (`Ids.newId()`). Save.

**Why upsert.** With the `(subscription, type, key)` UNIQUE constraint (migration 18), a naive insert of a repeated key would violate the constraint. The find-or-build pattern updates in place so a re-submitted override changes the value instead of erroring — and there's never more than one row per key, which is what makes resolution deterministic.

> Concurrency caveat: this is a *read-then-write* upsert, not a DB-level `INSERT ... ON CONFLICT`. Two concurrent `addOverride` calls for the same new key could both miss the `findBy...` and both try to insert, and one will hit the UNIQUE constraint → the loser's transaction fails (surfaced as a 409/500). That's acceptable (no data corruption) but worth knowing.

#### `private Object parseValue(String json)`

Jackson-deserializes the stored JSON back to a Java object for the DTO; on any parse error returns the raw string (graceful degradation, never throws). `null` in → `null` out.

### Entitlement resolution (feeds the license issuer)

#### `@Transactional(readOnly=true) ResolvedEntitlements resolveEntitlements(UUID subscriptionId, List<String> planPermissions, Map<String,Object> planFeatures)`

The merge that turns *plan baseline + overrides* into the final `permissions`/`features` embedded in the signed license JWT.

```
perms    = LinkedHashSet(planPermissions)      // baseline, order-preserving, dedup
features = LinkedHashMap(planFeatures)          // baseline copy

for each override ordered by (type, key):       // deterministic — P3
   PERMISSION: isTruthy(value) ? perms.add(key) : perms.remove(key)
   FEATURE   : features.put(key, value)          // override/replace plan value

return ResolvedEntitlements(List.copyOf(perms), features)
```

- Iterates overrides via the **ordered** finder so the result is independent of insertion order.
- `PERMISSION` overrides can both *grant* (add) and *revoke* (remove) a permission relative to the plan, decided by `isTruthy`.
- `FEATURE` overrides overwrite the plan's value for that key (last-writer per key, but there's only one row per key thanks to the unique constraint).
- Uses `LinkedHashSet`/`LinkedHashMap` so the output ordering is stable (baseline order then override-added keys) — important for reproducible JWT claims.

**Caller.** `LicenseClaimsBuilder.build(sub, ...)` calls this with the plan's permissions/features, then stamps the result into the JWT `permissions`/`features` claims. `LicenseIssuer.issue` only reaches here if `subscription.status == ACTIVE`.

#### `private boolean isTruthy(Object val)`

Lenient grant/revoke interpretation of a `PERMISSION` override's value:

| Input | Result |
|---|---|
| `null` | `true` (absence ⇒ grant) |
| `Boolean` | itself |
| `Number` | `intValue() != 0` |
| `String` | `false` only if (trimmed, lowercased) ∈ `{false, 0, no, revoke, deny}`; otherwise `true` |
| anything else | `true` |

So `{"type":"PERMISSION","key":"export","value":false}` (or `"deny"`, `"revoke"`, `0`) **removes** `export` from the plan baseline, while any present-and-truthy (or absent) value **adds** it. This lets overrides both extend and trim plan permissions through a single mechanism.

### Records

- `public record OverrideRequest(String type, String key, Object value)` — the write payload for an override (used by the controller and `createSubscription`).
- `public record ResolvedEntitlements(List<String> permissions, Map<String,Object> features)` — the merged result handed to the claims builder.

### DTO mapping

#### `@Transactional(readOnly=true) SubscriptionDto toDto(Subscription s)`

Looks up the plan (for `planCode`; `null`-safe if the plan is gone), loads overrides (`findBySubscriptionId`), parses each override value (`parseValue`), and assembles the full `SubscriptionDto`. Called by every read-returning controller method.

### Gotchas for this file

- **`EXPIRED` is recognized but never written here.** The state machine treats it as terminal, but no method in this package sets a *subscription* to `EXPIRED`. The only sweeper present, `LicenseLifecycleScheduler`, expires **license tokens**, not subscriptions, and uses `SubscriptionRepository` solely to look up `org_id` for event payloads. The closest thing to subscription expiry enforcement is that `LicenseClaimsBuilder` caps a new license's expiry at `subscription.endsAt` — so once `ends_at` passes you simply can't mint a license that outlives it, even though the subscription row stays `ACTIVE`. If you later add a subscription-expiry sweeper, route it through this service so it respects the state machine and (probably) cascades revocation like suspend/cancel do.
- **Reactivation never un-revokes licenses** (see `reactivate`). Always re-issue.
- **Override upsert is read-then-write** (see concurrency caveat above) — not atomic against a concurrent insert of the same new key; the DB unique constraint is the backstop.
- **`@Version` optimistic locking** on `Subscription`: a concurrent suspend+cancel on the same subscription will have one of them fail on version mismatch. Combined with the same-status short-circuit and the conditional revoke UPDATE, the system converges safely.
- All mutating methods populate `AuditContext` *before* returning; an `AuditInterceptor`/exception handler elsewhere turns that into the persisted audit row. If you add a new mutation, follow the `set/setTarget/putPayload` convention or it won't be audited.

---

## File: `SubscriptionController.java` cross-reference recap

(Documented in full above.) Calls only `SubscriptionService`; secured entirely via `@tenantAccess` (`TenantAccessChecker`); returns `SubscriptionDto`/`OverrideDto`/`204`. It is the only inbound HTTP entry point to the package.

---

## Appendix: data model (DDL)

From `db/changelog/changes/04-subscriptions.sql` and `18-subscription-overrides-unique.sql`:

```sql
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES plans(id),
    status  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
            CHECK (status IN ('ACTIVE','SUSPENDED','EXPIRED','CANCELLED')),
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    seats      INT,
    notes      TEXT,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- indexes: org_id, plan_id, status, ends_at

CREATE TABLE subscription_overrides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    type  VARCHAR(20) NOT NULL CHECK (type IN ('PERMISSION','FEATURE')),
    key   VARCHAR(128) NOT NULL,
    value_json JSONB
);
-- index: subscription_id
-- migration 18: UNIQUE (subscription_id, type, key)  ← ux_subscription_overrides_sub_type_key
```

Notes that matter for the code:
- **`org_id` FK is `ON DELETE CASCADE`** — deleting an org deletes its subscriptions; **`subscription_id` is `ON DELETE CASCADE`** — deleting a subscription deletes its overrides. The application rarely hard-deletes (it uses status transitions), but the DB will clean up overrides if a subscription is ever deleted.
- The `CHECK (status IN (...))` mirrors the `Status` enum stored as STRING; adding a new enum value requires a migration to widen the check.
- The `@Version` column (`version`) is added by its own migration (not shown here, in the `@Version`/`@Version`-adding changeset of the P1/P2 wave); the entity declares it `NOT NULL`.
- Migration 18 also defensively dedupes pre-existing `(subscription_id, type, key)` duplicates (keeping the smallest `id`) before adding the unique constraint — a no-op on a fresh DB, relevant only on upgrade.

## Appendix: domain events emitted

| Event | Aggregate | Emitted by | Payload highlights |
|---|---|---|---|
| `SubscriptionActivated` | `subscription` | `createSubscription` | subscription/org/plan ids, plan_code, window, seats |
| `SubscriptionSuspended` | `subscription` | `suspend` | subscription_id, reason |
| `SubscriptionReactivated` | `subscription` | `reactivate` | subscription_id |
| `SubscriptionCancelled` | `subscription` | `cancel` | subscription_id, reason |
| `LicenseRevoked` | `license_token` | cascade via `LicenseRevocationService.markRevoked` | jti, subscription_id, reason, revoked_at |

All enqueued through `OutboxPublisher` in the same transaction as the triggering write, then delivered asynchronously by the outbox worker.
