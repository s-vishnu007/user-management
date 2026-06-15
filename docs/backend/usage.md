# `com.example.cp.usage` — Usage Ingestion & Quota Enforcement

## Module overview

This package is the **metering subsystem** of the control panel. Customer Docker apps (running the offline license verifier) phone home to report *how much* of a metered feature they have consumed — API calls, seats, GB processed, whatever each `featureKey` represents — and this package (a) records each consumption as an immutable, idempotent **usage event**, and (b) rolls those events up into a per-month **usage quota** row that tracks `consumed_value` against an optional `limit_value`, *atomically refusing* the increment when it would breach the cap. It also serves a read API that hands an admin/UI a combined report of recent events plus current quota status for a subscription.

There are exactly eight source files, and they form three layers:

| Layer | Files | Role |
|-------|-------|------|
| **Web** | `UsageIngestController` | REST surface (`POST /usage/ingest`, `GET /subscriptions/{id}/usage`), request/response DTOs, validation, tenant authorization, audit. |
| **Service** | `UsageIngestService` | The brain: validation, in-batch + cross-request dedup, the atomic guarded upsert that enforces limits, quota/event read paths. |
| **Persistence + lookup** | `UsageEvent`, `UsageQuota`, `UsageEventRepository`, `UsageQuotaRepository`, `LicenseTokenLookup`, `LicenseTokenView` | JPA entities, Spring Data repositories (incl. one pessimistic-lock query and one native bounded range query), and a thin read-only projection of `LicenseToken` so this package does not reach into the licenses entity directly. |

The single most important idea in the whole package is the **TOCTOU-safe limit enforcement** in `UsageIngestService.upsertQuotaEnforcingLimit` — a Postgres `INSERT … ON CONFLICT … DO UPDATE … WHERE consumed+add <= limit`. That one SQL statement is what makes "two concurrent batches cannot both overrun the cap" true. Everything else exists to feed it clean, de-duplicated input and to translate its outcomes into HTTP.

### How it fits the bigger picture

```
 Customer Docker app (license-verifier SDK)
        │  POST /api/v1/usage/ingest  { jti, events[] }   (API key auth)
        ▼
 UsageIngestController ──@PreAuthorize──► TenantAccessChecker  (jti → subscription → org → caller's org?)
        │
        ▼
 UsageIngestService.ingest(jti, events)   @Transactional
        │  1. LicenseTokenLookup.findByJti → LicenseTokenView (active?)
        │  2. validate + dedup (batch Set + UsageEventRepository.existsBy…)
        │  3. eventRepo.saveAll + flush   ── unique index = idempotency backstop
        │  4. aggregate per (feature, month) bucket
        │  5. upsertQuotaEnforcingLimit() per bucket  ── ATOMIC guarded upsert
        ▼
 PostgreSQL: usage_events (append-only) + usage_quotas (running totals)

 Admin UI / API consumer
        │  GET /api/v1/subscriptions/{subId}/usage?from&to&featureKey
        ▼
 UsageIngestController.listUsage → service.listEvents + getQuotaStatus → UsageReport
```

- **Upstream**: the `licenses` package owns `LicenseToken`/`LicenseTokenRepository`. This package only *reads* a license by `jti` (via `LicenseTokenLookup`) to map an inbound report to a `subscriptionId` and check the license is live. It never mutates licenses.
- **Security**: authorization is delegated entirely to `com.example.cp.security.TenantAccessChecker` (the `@tenantAccess` SpEL bean). Ingest and read are both *tenant-scoped* — a caller bound to org A cannot meter or read org B's subscription even if it holds the global `usage.ingest`/`usage.read` authority.
- **Cross-cutting**: `com.example.cp.common` supplies `ApiException` (RFC-7807-ish error mapping), `Ids.newId()` (time-ordered UUIDv7 keys), and `AuditContext` (the per-request audit ThreadLocal an interceptor later flushes to the `audit_events` table).
- **Schema**: tables and constraints live in Liquibase changesets `07-usage.sql` (base tables) and `13-usage-integrity.sql` (the `event_id` dedup column + partial unique index + non-negative CHECKs + RBAC permission seeds). Read those alongside this doc — several invariants the Java code relies on are enforced in SQL.

---

## File-by-file

### `UsageEvent.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/UsageEvent.java`
**Responsibility:** JPA entity for the append-only `usage_events` table — one row per accepted consumption report.

It is a Lombok-decorated entity (`@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`) mapped to `usage_events`. Fields:

| Field | Column | Notes |
|-------|--------|-------|
| `id` | `id UUID` (PK) | Application-assigned via `Ids.newId()` (UUIDv7 time-ordered) rather than the DB `gen_random_uuid()` default, so rows are roughly insert-ordered for index locality. |
| `subscriptionId` | `subscription_id UUID NOT NULL` | FK → `subscriptions(id) ON DELETE CASCADE`. Resolved from the license, never sent by the client. |
| `jti` | `jti VARCHAR(64)` | The license id this report came in under. Indexed (`idx_usage_events_jti`). |
| `eventId` | `event_id VARCHAR(120)` | **Optional client-supplied idempotency key.** Part of the partial unique dedup index. Nullable — clients that don't send one get no cross-request dedup. |
| `featureKey` | `feature_key VARCHAR(64) NOT NULL` | The metered feature. |
| `quantity` | `quantity NUMERIC NOT NULL` | `BigDecimal`. DB CHECK `quantity >= 0`; the service additionally rejects `<= 0`. |
| `occurredAt` | `occurred_at TIMESTAMPTZ NOT NULL` | When the consumption happened (client clock, validated against a sane window). |
| `metadataJson` | `metadata_json JSONB` | Arbitrary client metadata, serialized to a JSON string. `@JdbcTypeCode(SqlTypes.JSON)` tells Hibernate to bind the `String` as `jsonb`. |

**Gotchas:**
- The `(subscription_id, jti, event_id)` **partial unique index** (`ux_usage_events_dedup … WHERE event_id IS NOT NULL`, from `13-usage-integrity.sql`) is *not* declared on the entity — it lives only in SQL. It is the authoritative idempotency backstop. Because it's partial, rows with `event_id = NULL` are never de-duplicated, which is intentional (no key = no idempotency promise).
- `metadataJson` is stored as a raw `String`; the service is responsible for producing valid JSON (it uses Jackson). Reading it back gives you the string, not a parsed map.

---

### `UsageQuota.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/UsageQuota.java`
**Responsibility:** JPA entity for `usage_quotas` — the running per-(subscription, feature, period) accumulator that the limit is enforced against. **One row per month per feature.**

A composite-key entity using `@IdClass(UsageQuota.PK.class)`. The three `@Id` columns are `subscriptionId`, `featureKey`, and `periodStart` (matching the table PK `(subscription_id, feature_key, period_start)`).

| Field | Column | Notes |
|-------|--------|-------|
| `subscriptionId` / `featureKey` / `periodStart` | composite PK | `periodStart` is the UTC month boundary computed by the service (`monthStartUtc`). |
| `periodEnd` | `period_end TIMESTAMPTZ NOT NULL` | `periodStart + 1 month`. Refreshed by the upsert (`EXCLUDED.period_end`). |
| `limitValue` | `limit_value NUMERIC` (nullable) | **NULL means "no cap" / unlimited.** DB CHECK: NULL or `>= 0`. |
| `consumedValue` | `consumed_value NUMERIC NOT NULL` | Running total. DB CHECK `>= 0`. |

**`public static class PK implements Serializable`** — the `@IdClass`. Plain no-arg + all-args constructors and hand-written `equals`/`hashCode` over the three key components (required by JPA for composite keys; Lombok is not used here because JPA needs a specific contract on the id class).

**Why this shape (the big gotcha):** quotas are **per period**, so for a single `(subscription, feature)` pair there is *one row per month*. This is the source of a class of bugs the repository comments call out: any "find by subscription+feature" query that assumes a *single* result will throw `IncorrectResultSizeDataAccessException` (→ HTTP 500) the moment the subscription rolls into a second month. See `UsageQuotaRepository`.

**Who writes it:** almost never JPA. The accumulate path goes through `UsageIngestService.upsertQuotaEnforcingLimit` using a *raw JDBC* `INSERT … ON CONFLICT` (see below) — the entity exists mainly for the *read* path and for the pessimistic-lock query. So treat `UsageQuota` as a read model in practice.

---

### `LicenseTokenView.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/LicenseTokenView.java`
**Responsibility:** An immutable, read-only **projection** of `com.example.cp.licenses.LicenseToken`, so the usage package depends on a tiny DTO instead of the full licenses entity / its `Status` enum.

```java
public record LicenseTokenView(UUID id, String jti, UUID subscriptionId,
                               String status, OffsetDateTime expiresAt, OffsetDateTime revokedAt)
```

- `status` is the `LicenseToken.Status` enum *flattened to its name* (`"ACTIVE"`, `"REVOKED"`, `"EXPIRED"`), so the usage package doesn't import that enum.
- **`isActive()`** — the gate `UsageIngestService` uses to decide whether to accept ingestion. A token is active iff:
  - `status == "ACTIVE"`, **and**
  - `revokedAt == null` (not revoked), **and**
  - `expiresAt == null || expiresAt.isAfter(now)` (not expired).

  This is defense-in-depth: even if the stored `status` lags behind reality (e.g. a token whose `expiresAt` has passed but whose row still says `ACTIVE` because the lifecycle sweeper hasn't run), the time check catches it. Note `OffsetDateTime.now()` is evaluated at call time, in the JVM's default offset — fine because the comparison is instant-based.

**Collaborators:** produced by `LicenseTokenLookup`; consumed by `UsageIngestService.ingest`.

---

### `LicenseTokenLookup.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/LicenseTokenLookup.java`
**Responsibility:** The one adapter between this package and the `licenses` package. A `@Component` that wraps `LicenseTokenRepository` and maps a `LicenseToken` to a `LicenseTokenView`.

```java
public Optional<LicenseTokenView> findByJti(String jti)
```

- Calls `repo.findByJti(jti)` and `map`s the entity into the view, defensively null-guarding the status enum (`t.getStatus() == null ? null : t.getStatus().name()`).
- Returns `Optional.empty()` for an unknown `jti` — the caller turns that into a 400 "Unknown jti".

**Why it exists:** keeps the dependency direction clean (usage → a narrow view, not usage ↔ licenses entity graph) and gives a single seam to mock in tests. `LicenseTokenRepository.findByJti` is the actual DB query (also used by `TenantAccessChecker.resolveOrgForJti`).

---

### `UsageEventRepository.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/UsageEventRepository.java`
**Responsibility:** Spring Data JPA repository for `UsageEvent`. Two non-trivial members.

**`int MAX_EVENTS = 1000`** — a documented hard cap on a single listing. `usage_events` is a high-volume append-only table; an unbounded `SELECT *` over a busy subscription could pull millions of rows and OOM the process or the response. Callers wanting more must narrow the `from`/`to` window. (Note: the constant documents intent, but the `LIMIT` in the query below is a literal `1000` — keep them in sync if you change one.)

**`findInRange(subId, from, to)`** — the listing query backing `GET …/usage`:

```sql
SELECT * FROM usage_events
WHERE subscription_id = :subId
  AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
  AND (CAST(:to   AS timestamptz) IS NULL OR occurred_at <  :to)
ORDER BY occurred_at DESC
LIMIT 1000
```

- **Native query, on purpose.** In JPQL a bare nullable parameter used as `:from IS NULL` gives Postgres no type to infer → `could not determine data type of parameter`. The explicit `CAST(:from AS timestamptz)` fixes that while keeping the optional open-ended-range semantics (pass `null` for either bound to leave it open).
- Range is **`[from, to)`** — inclusive lower, exclusive upper — which composes cleanly when tiling adjacent windows (no double counting at boundaries).
- Returns the **most-recent 1000** rows (`ORDER BY occurred_at DESC LIMIT 1000`). It backs `idx_usage_events_subscription_occurred (subscription_id, occurred_at)`.

**`existsBySubscriptionIdAndJtiAndEventId(subId, jti, eventId)`** — derived query used as the **cheap pre-check** in the dedup path. It returns true if a row with that idempotency triple already exists; the service skips re-inserting it. This is best-effort (a concurrent request can still slip past between the check and the insert) — the *authoritative* guarantee is the unique index, handled via the `DataIntegrityViolationException` path in the service.

---

### `UsageQuotaRepository.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/UsageQuotaRepository.java`
**Responsibility:** Spring Data JPA repository for `UsageQuota` (keyed by the composite `UsageQuota.PK`). Three methods, each with a deliberate signature.

| Method | Returns | Why |
|--------|---------|-----|
| `findBySubscriptionId(UUID)` | `List<UsageQuota>` | All quota rows (every feature, every period) for a subscription — the "no featureKey filter" read path. |
| `findBySubscriptionIdAndFeatureKey(UUID, String)` | **`List<UsageQuota>`** | **Must be a list.** One row accumulates *per month* for a `(subscription, feature)` pair. The earlier single-result `Optional` flavour threw `IncorrectResultSizeDataAccessException` → 500 the instant a second period existed. The class comment hammers this point. |
| `findForUpdate(subId, featureKey, periodStart)` | `Optional<UsageQuota>` | `@Lock(PESSIMISTIC_WRITE)` JPQL `SELECT … WHERE sub AND feature AND periodStart`. **Single** result is correct here because all three PK components are pinned, so it identifies exactly one row. |

**About `findForUpdate`:** it takes a `SELECT … FOR UPDATE` row lock and is the JPA-native way to do read-then-write under a lock. **However, the live ingest path does *not* use it** — `UsageIngestService.upsertQuotaEnforcingLimit` instead performs an atomic `INSERT … ON CONFLICT … DO UPDATE … WHERE` via `JdbcTemplate`, which is strictly stronger (it also covers the *insert* race for a brand-new period, which a `SELECT FOR UPDATE` on a not-yet-existing row cannot lock). `findForUpdate` remains available for callers that genuinely need to load-modify-store the entity under a pessimistic lock; new engineers should prefer the atomic upsert pattern for accumulation.

---

### `UsageIngestController.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestController.java`
**Responsibility:** The REST surface. `@RestController` at base path `/api/v1`. Two endpoints, all the request/response DTOs, bean-validation rules, tenant authorization, and audit wiring.

#### `POST /usage/ingest` → `ingest(@Valid IngestRequest body)`

Authorization (SpEL):
```java
@PreAuthorize("hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#body.jti())")
```
Two AND-ed conditions: the caller must hold the global `usage.ingest` authority **and** pass the per-resource tenant check. `canIngestUsageForJti` resolves `jti → license → subscription → owning org` and confirms the caller (super-admin, or an API key bound to that org, or an org member) is allowed against *that* org. **This closes the cross-tenant IDOR**: an API key for org A literally cannot ingest against a `jti` belonging to org B, regardless of authorities. (See `TenantAccessChecker` notes below.)

Flow:
1. Map each wire `EventDto` → the service's `UsageIngestService.IngestEvent` record (a deliberate decoupling of the HTTP DTO from the service DTO).
2. Call `service.ingest(body.jti(), events)`.
3. **Catch `DedupCollisionException`** (a concurrent request won the dedup race and already persisted these events; the `@Transactional` ingest rolled back). Instead of a raw 500, synthesize an **idempotent** `IngestResult(0, collision.subscriptionId)` — zero newly accepted, no work lost.
4. Write audit context: action `usage.ingested`, target `subscription/<id>`, payload `events_count`. (`AuditContext` is a ThreadLocal an interceptor flushes to `audit_events` after the request.)
5. Respond **`202 Accepted`** with `IngestResponse(eventsAccepted, subscriptionId)`. 202 (not 201) signals "accepted for metering" — the semantically honest code given dedup/limit outcomes are part of normal operation.

#### `GET /subscriptions/{subId}/usage` → `listUsage(...)`

Authorization:
```java
@PreAuthorize("(hasAuthority('subscription.read') or hasAuthority('usage.read')) "
            + "and @tenantAccess.canReadSubscription(#subId)")
```
Either read authority is sufficient, **AND** the caller must pass `canReadSubscription(subId)` (which resolves the *target* subscription's org and authorizes against it). Comment explicitly notes the global authority is no longer a cross-org bypass — mirrors `LicenseController.list`.

Query params: optional `from`/`to` (`@DateTimeFormat ISO.DATE_TIME`) and optional `featureKey`. It calls `service.listEvents(subId, from, to)` and `service.getQuotaStatus(subId, featureKey)` and assembles a `UsageReport`.

#### DTOs and their validation (the "fail at 400, not 500" theme)

Every size bound mirrors a DB column width so that an oversized value is rejected as a **400 here** rather than blowing up downstream as a `DataIntegrityViolationException` → 500:

- **`IngestRequest(jti, events)`** — `jti` `@NotBlank @Size(max = 64)` (matches `license_tokens.jti VARCHAR(64)`); `events` `@NotEmpty List<@Valid EventDto>` (the `@Valid` cascades validation into each element).
- **`EventDto(eventId, featureKey, quantity, occurredAt, metadata)`**:
  - `eventId` `@Size(max = 120)` (`event_id VARCHAR(120)`), optional.
  - `featureKey` `@NotBlank @Size(max = 64)` (`feature_key VARCHAR(64)`).
  - `quantity` `@NotNull @DecimalMin(value="0", inclusive=false)` (strictly > 0) `@Digits(integer=19, fraction=6)` — caps precision/scale so an absurd `BigDecimal` is a 400, not a DB-cast 500.
  - `occurredAt` optional (service defaults to "now"); `metadata` is a free-form `Map<String,Object>`.
- **`IngestResponse(eventsAccepted, subscriptionId)`** — the 202 body.
- **`UsageReport(events, quotas)`** with nested `EventDto` and `QuotaDto` (each with a `static from(...)` mapper from the entity). Note `UsageReport.EventDto` is a *different* record from the request `EventDto` and intentionally **omits `metadata`** from the read response.

**Gotchas for a new engineer:**
- There are *two* `EventDto` records in this file (the inbound request one and the nested response one in `UsageReport`). Don't confuse them — the request one carries `metadata` + validation; the response one carries `id`/`jti` and no metadata.
- Validation here is necessary but **not the whole story** — the service re-validates `featureKey`, `quantity`, and `occurredAt` (the time-window check is service-only, since it depends on `now` and config). Bean validation guards the wire; the service guards business rules.

---

### `UsageIngestService.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestService.java`
**Responsibility:** The transactional core — license check, validation, idempotent dedup, atomic limit-enforcing accumulation, and the two read paths. This is the file to understand deeply.

**Collaborators injected:** `UsageEventRepository`, `UsageQuotaRepository`, `LicenseTokenLookup`, a `JdbcTemplate` (for the raw upsert), plus three config-driven fields:

| Field | Property (default) | Meaning |
|-------|--------------------|---------|
| `enforceLimit` | `app.usage.enforce-limit` (**true**) | When true, the guarded upsert refuses over-cap increments (409). When false, the `WHERE` guard is dropped and consumption always accumulates (metering-only mode). |
| `occurredMaxPast` | `app.usage.occurred-at-max-past` (**P35D**) | How far in the past an `occurredAt` may be. ~35 days covers a full billing month plus slack for late/offline batches. |
| `occurredMaxFuture` | `app.usage.occurred-at-max-future` (**PT5M**) | How far in the future `occurredAt` may be — 5 minutes of clock-skew tolerance. |

A private `ObjectMapper` is instantiated directly (not injected) for metadata serialization.

**Nested types:**
- `record IngestEvent(eventId, featureKey, quantity, occurredAt, metadata)` — the service-layer input DTO.
- `record IngestResult(eventsAccepted, subscriptionId)` — the result.
- `static final class DedupCollisionException extends RuntimeException` — carries the `subscriptionId`; signals the unique-index race (see below).

#### `@Transactional IngestResult ingest(String jti, List<IngestEvent> events)`

The whole method runs in one transaction; any thrown `ApiException`/`DedupCollisionException` rolls back **everything** (event rows *and* quota increments), which is exactly what you want for all-or-nothing batch semantics.

Step by step:

1. **Guard inputs** — blank `jti` → 400; empty `events` → 400.
2. **Resolve & check the license** — `tokenLookup.findByJti(jti)` → 400 "Unknown jti" if absent; `!token.isActive()` → 400 "License token is not active". The `subscriptionId` is taken from the token (the client never supplies it — this is the anti-spoofing root). `now = OffsetDateTime.now()`.
3. **Validate + dedup each event** into `toPersist`:
   - `featureKey` blank → 400; `quantity` null or `signum() <= 0` → 400 (rejects zero and negatives even though the DB CHECK only enforces `>= 0`).
   - `occurred = e.occurredAt() ?? now`. If `occurred < now - occurredMaxPast` or `occurred > now + occurredMaxFuture` → 400 "occurredAt is outside the accepted window".
   - **Dedup** (only when an `eventId` is present; blank → treated as `null` = no dedup):
     - `batchEventIds.add(eventId)` false → duplicate *within this batch* → `continue` (skip).
     - `eventRepo.existsBySubscriptionIdAndJtiAndEventId(subId, jti, eventId)` true → *already ingested in a prior request* → `continue`.
   - Otherwise build a `UsageEvent` (`Ids.newId()` PK, subscription from the token, serialized metadata) and add to `toPersist`.
4. **Fully-deduplicated replay short-circuit** — if `toPersist` is empty, return `IngestResult(0, subId)` (a no-op replay; still 202).
5. **Persist events** — `eventRepo.saveAll(toPersist); eventRepo.flush()`. The explicit `flush()` forces the INSERTs (and thus any unique-index collision) to surface *now*, inside this try/catch, rather than at transaction commit. A `DataIntegrityViolationException` here → wrap and throw `DedupCollisionException(subId, dup)` (a concurrent request slipped the same `eventId` past the pre-check; see the exception section for why we don't retry).
6. **Aggregate per bucket** — group the new events by `featureKey + "|" + monthStartUtc(occurredAt)` into `addByBucket` (summing quantities with `BigDecimal::add`) and keep one `bucketSample` event per bucket (for its `featureKey`/`occurredAt`). `LinkedHashMap` preserves deterministic ordering. Aggregating first means **one guarded UPDATE per bucket** instead of one per event.
7. **Accumulate + enforce** — for each bucket call `upsertQuotaEnforcingLimit(subId, featureKey, summedQty, sampleOccurredAt)`.
8. Return `IngestResult(toPersist.size(), subId)`.

#### `private void upsertQuotaEnforcingLimit(subId, featureKey, add, occurredAt)` — the crown jewel

Computes `periodStart = monthStartUtc(occurredAt)` and `periodEnd = periodStart + 1 month`, then runs (via `JdbcTemplate`):

```sql
INSERT INTO usage_quotas (subscription_id, feature_key, period_start, period_end, limit_value, consumed_value)
VALUES (?, ?, ?, ?, NULL, ?)
ON CONFLICT (subscription_id, feature_key, period_start)
DO UPDATE SET consumed_value = usage_quotas.consumed_value + EXCLUDED.consumed_value,
              period_end     = EXCLUDED.period_end
-- when enforceLimit is true, additionally:
WHERE usage_quotas.limit_value IS NULL
   OR usage_quotas.consumed_value + EXCLUDED.consumed_value <= usage_quotas.limit_value
```

Why this is correct and concurrency-safe:

- **Fresh period** → the `INSERT` succeeds with `limit_value = NULL` (no cap on a freshly-seen period; a limit is presumably set out-of-band) and `consumed_value = add`. A fresh insert always reports **1 row affected**.
- **Existing period** → `ON CONFLICT` fires the `DO UPDATE`, which (a) takes a **row lock** for the duration of the update, and (b) increments `consumed_value` *only if* the `WHERE` guard holds. Because the lock is held across the read-and-write, the check (`consumed + add <= limit`) and the set are **indivisible** — this is what eliminates the TOCTOU race that a plain `SELECT consumed` → compute → `UPDATE` would allow (two batches both reading a stale `consumed` and both passing the check). `EXCLUDED` refers to the would-be-inserted values, so `EXCLUDED.consumed_value` is `add`.
- **Over-limit detection** — `jdbc.update` returns affected-row count. For an existing row whose guard *excluded* it, the `DO UPDATE` matches **0 rows**. Since a fresh INSERT always reports 1, `affected == 0` *uniquely* means "row existed and the limit guard blocked it". So:
  ```java
  if (enforceLimit && affected == 0) throw ApiException.conflict("Usage limit exceeded for feature '…'");
  ```
  → **409**, which rolls back the entire `@Transactional` ingest (including the already-saved event rows). All-or-nothing: a batch that would breach the cap is rejected wholesale.
- **Enforcement off** — the `WHERE` clause is omitted entirely, so the `DO UPDATE` always matches and consumption accumulates unconditionally (pure metering, no rejection).

**Edge notes:**
- The cap is enforced **per bucket (feature × month)** independently. A batch spanning two months touches two buckets and either could 409.
- `consumed_value + EXCLUDED.consumed_value <= limit_value` is **inclusive** — consuming *exactly up to* the limit is allowed; the increment that would push it *over* is the one that fails.
- This writes via raw JDBC, bypassing the JPA persistence context. Within the same transaction the `UsageQuota` entity state is not auto-synced, but the read path uses fresh queries so that's a non-issue here.

#### `DedupCollisionException` (nested) — the idempotency backstop

Thrown when `saveAll/flush` hits the partial unique index `(subscription_id, jti, event_id)`. The detailed comment explains the constraint that makes the design what it is: **Postgres aborts the entire transaction on a constraint violation** — no further statements can run on that connection — so you *cannot* catch it, re-query, and partially re-ingest within the same transaction. Therefore the chosen behavior is:

- Let the exception propagate out of `ingest` so the `@Transactional` proxy rolls back the (already-aborted) transaction.
- The **controller** catches it and returns an **idempotent `202` with zero newly-accepted**. The concurrent "winner" already persisted the rows and accumulated the quota, so **no work is lost** — the loser just reports it added nothing.

This is the deliberate two-tier idempotency strategy: the `existsBy…` pre-check (step 3) handles the common case cheaply; the unique index + this exception handle the genuine concurrent-duplicate race correctly.

#### Helpers

- **`monthStartUtc(OffsetDateTime t)`** — normalizes any instant to the **first day of its month at 00:00 UTC**: `t.withOffsetSameInstant(UTC).withDayOfMonth(1).truncatedTo(DAYS)`. Converting to UTC *first* means the period boundary is deterministic regardless of the client's offset — two clients in different time zones reporting "the same UTC instant" land in the same bucket. This single method defines the billing period for both event bucketing and quota keys; change it and you change what "a period" means everywhere.
- **`serializeMetadata(Map)`** — null/empty → `null` (stored as SQL NULL `jsonb`); otherwise Jackson `writeValueAsString`. A `JsonProcessingException` becomes a 400 "Invalid metadata: …" (the only place client metadata can fail).

#### Read paths

- **`@Transactional(readOnly=true) getQuotaStatus(subscriptionId, featureKey)`** — blank `featureKey` → `findBySubscriptionId` (all features, all periods); otherwise `findBySubscriptionIdAndFeatureKey` (**list** — comment reiterates a single-result query threw after the 2nd month).
- **`@Transactional(readOnly=true) listEvents(subscriptionId, from, to)`** — delegates to `eventRepo.findInRange` (bounded to the most-recent 1000 rows).

**Gotchas:**
- The over-limit signal is purely the **affected-row count == 0** heuristic; it's only valid because a fresh INSERT reports 1 and a guard-blocked UPDATE reports 0. If you ever change the SQL (e.g. add other `WHERE` conditions or a partial-conflict target), re-verify this invariant or you'll either silently drop usage or throw spurious 409s.
- `enforceLimit` defaulting to `true` means in a default deployment, exceeding a *set* limit returns 409 and the whole batch is dropped. Operators wanting "meter but never block" must set `app.usage.enforce-limit=false`.

---

## Cross-package collaborators (for context)

These live outside the package but are essential to the flow:

- **`com.example.cp.security.TenantAccessChecker`** (bean `@tenantAccess`) — the authorization brain referenced by both `@PreAuthorize` expressions. It is **default-deny** and deliberately ignores global authorities for *resolution*: it maps the target (`jti` → license → subscription → org, or subscription → org) and authorizes against that org only — super-admin (global bypass), API key bound to that org, or org membership/rank. `canIngestUsageForJti` and `canReadSubscription` are the two methods used here. This is what makes usage ingest/read tenant-safe.
- **`com.example.cp.licenses.LicenseToken` / `LicenseTokenRepository`** — the source of truth behind `LicenseTokenLookup` and `TenantAccessChecker.resolveOrgForJti`. `findByJti` is the shared entry point; `LicenseToken` is `@Version`-optimistic-locked (relevant to licenses, not directly to usage).
- **`com.example.cp.common`** — `ApiException` (factory methods `badRequest`/`conflict`/… mapped to HTTP statuses by the global handler), `Ids.newId()` (UUIDv7 time-ordered ids for event PKs), `AuditContext` (per-request ThreadLocal whose `action`/`target`/`payload` an audit interceptor persists to `audit_events`).
- **Liquibase** — `07-usage.sql` (base tables + `idx_usage_events_subscription_occurred`, `idx_usage_events_jti`), `13-usage-integrity.sql` (the `event_id` column, the partial unique dedup index `ux_usage_events_dedup`, the non-negative CHECK constraints on quantity/consumed/limit, and the RBAC seed granting `usage.read`/`usage.ingest`/`license.read` to the role hierarchy — note `usage.ingest` is granted to `ORG_ADMIN`+ but *not* `ORG_MEMBER`/`VIEWER`, who only get `usage.read`).

## Quick reference: invariants a new engineer must not break

1. **`subscriptionId` always comes from the license**, never the client — the anti-spoofing root.
2. **Quotas are per (subscription, feature, month)** — any single-result query over (subscription, feature) is a latent 500.
3. **Limit enforcement is the atomic `INSERT … ON CONFLICT … WHERE`** — don't "optimize" it into SELECT-then-update; that reintroduces the TOCTOU overrun.
4. **`affected == 0` == over-limit** is a heuristic that depends on the exact SQL — preserve it if you touch the upsert.
5. **Idempotency is two-tier**: cheap `existsBy…` pre-check + authoritative partial unique index surfaced as `DedupCollisionException` → idempotent 202.
6. **All-or-nothing batch**: a 409 (or dedup collision) rolls back the *entire* transaction, events included.
7. **Period boundary is UTC-month** via `monthStartUtc` — the one definition of "a period".
