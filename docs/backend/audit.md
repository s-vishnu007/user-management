# `com.example.cp.audit` — Append-Only Audit Trail

## Module overview

This package is the **security audit trail** of the control panel: a single append-only table (`audit_log`) that records *who did what to which target, from where, when, and with what outcome* for every state-changing operation in the API. It is built for **tamper-evidence and compliance**, not just debugging — the table is protected by a PostgreSQL `BEFORE UPDATE/DELETE` trigger that rejects all mutation, with exactly one narrowly-scoped exception for GDPR Art. 17 erasure (scrubbing PII out of an erased subject's retained rows, never touching identity/integrity columns and never permitting `DELETE`).

The package contains six files that together form three layers:

| Layer | Files | Role |
|-------|-------|------|
| **Write path** | `AuditWriter`, `AuditOutcome` | Persist rows; choose fail-open vs. fail-closed semantics |
| **Auto-capture** | `AuditInterceptor` (AOP aspect) | Wrap every mutating controller method and emit a row if one wasn't already written explicitly |
| **Read path** | `AuditController`, `AuditLogRepository`, `AuditLog` | Filtered, paginated, RBAC-/tenant-scoped search; the JPA entity + the GDPR-redaction repository method |

Two collaborators that live in `com.example.cp.common` are part of this subsystem's design even though they sit in a different package, and are covered in detail below because the audit package is meaningless without them: **`AuditContext`** (the per-request ThreadLocal that explicit call sites populate) and **`AuditProperties`** (config for trusted proxies + fail-closed action allowlist). The immutability/redaction **DB trigger** (defined across `08-audit.sql` and `18-webhook-fanout-integrity.sql`) is the load-bearing security control and is documented in its own section.

### How it fits the bigger picture

Almost every service and controller in the application feeds this package. There are two ways a row gets written:

1. **Implicitly**, by the `AuditInterceptor` AOP aspect, which fires after any `@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping` controller method returns or throws. This is the safety net: even if a developer forgets to audit a mutation, the aspect derives a sensible action string and writes a row.
2. **Explicitly**, by a service or controller calling `AuditWriter.record(...)` directly (e.g. `AuthController` writing `auth.login.failed`) and then calling `AuditContext.markRecorded()` so the aspect *and* the `GlobalExceptionHandler` fallback do not write a duplicate row for the same request.

The read side is consumed by the React admin UI via `GET /api/v1/audit` (global, requires the `audit.read` authority) and `GET /api/v1/orgs/{orgId}/audit` (tenant-scoped, available to org owners/admins). The GDPR redaction method is invoked only from `com.example.cp.compliance.ErasureService`.

```
 explicit call sites                         AOP safety net
 (AuthController, SsoService,                 (AuditInterceptor
  ScimService, BillingService, ...)            on mutating endpoints)
        |  AuditWriter.record(...)                  |  emit(...) -> writer.record(...)
        |  AuditContext.markRecorded()              |  (skipped if AuditContext.isRecorded())
        v                                            v
                    AuditWriter  ──INSERT──>  audit_log  <──UPDATE(redact only)── ErasureService
                                                  ^
                                                  |  BEFORE UPDATE/DELETE trigger
                                                  |  audit_log_block_modifications()
                                                  |  (rejects all mutation except GDPR redaction)
                                                  |
                    AuditController  ──SELECT──> AuditLogRepository.search()/searchForOrg()
```

---

## `audit/AuditOutcome.java`

### Responsibility
A tiny enum representing the **outcome dimension** of an audit event. Persisted to `audit_log.outcome` as a `VARCHAR(16)` (via `name()`), and a `CHECK` constraint in the DB mirrors the three values.

### `public enum AuditOutcome`

| Value | Meaning | When chosen |
|-------|---------|-------------|
| `SUCCESS` | The audited action completed normally. | `@AfterReturning` advice; the default when `outcome == null`. |
| `DENIED` | Authn/authz refusal. | `AccessDeniedException`, `AuthenticationException`, or an `ApiException` with HTTP 401/403. |
| `FAILED` | Any other thrown exception (validation 4xx, 5xx, business errors not classified as auth). | Everything else in `@AfterThrowing`. |

### Gotchas
- The string values are **load-bearing**: they appear in three places that must stay in lockstep — the enum constants, the `@Enumerated(EnumType.STRING)` mapping on `AuditLog.outcome`, and the DB `CHECK (outcome IN ('SUCCESS','DENIED','FAILED'))` from `13-audit-outcome.sql`. Renaming a constant or adding a value requires a migration to widen the check, or inserts will fail.
- The classification logic lives in `AuditInterceptor.outcomeFor(...)`, not here. This enum is a pure data type.

---

## `audit/AuditLog.java`

### Responsibility
The **JPA entity** mapping the `audit_log` table. Used purely for **reads** (search results decode into this entity). It is deliberately *not* used for inserts — `AuditWriter` uses raw JDBC instead (see why below).

### `@Entity @Immutable public class AuditLog`

Annotations worth understanding:
- `@org.hibernate.annotations.Immutable` — tells Hibernate this entity is read-only. Hibernate will never generate `UPDATE` statements for it and skips dirty-checking. This is the ORM-level expression of the table's append-only nature; the *real* enforcement is the DB trigger (an ORM annotation can't stop raw SQL).
- `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` (Lombok) — the setters/all-args/builder exist mostly for test fixtures and the mapper; they do not imply the row is mutable in production.

### Fields / column mapping

| Field | Column | Type / notes |
|-------|--------|--------------|
| `id` | `id` (PK, `nullable=false`) | `UUID`. **Not** auto-generated by JPA here — `AuditWriter` supplies the id via `Ids.newId()`; the DB also has a `DEFAULT gen_random_uuid()` as a backstop. |
| `actorUserId` | `actor_user_id` | `UUID`, nullable (system/anonymous events have no actor, e.g. a failed login before identity is known). |
| `actorOrgId` | `actor_org_id` | `UUID`, nullable. Drives tenant-scoped search (`searchForOrg`). |
| `action` | `action` (`nullable=false`, len 128) | Dotted action code, e.g. `auth.login`, `subscription.cancel`, or a derived `POST /api/v1/...` fallback. |
| `targetType` | `target_type` (len 64) | e.g. `user`, `org`, `subscription`. |
| `targetId` | `target_id` (len 128) | Stringified id of the target (often a UUID, but a string so non-UUID targets fit). |
| `payloadJson` | `payload_json` (`jsonb`) | `@JdbcTypeCode(SqlTypes.JSON)`; arbitrary JSON-serialized context map. **PII can live here** — this is one of the two columns the GDPR redaction scrubs. |
| `ipAddress` | `ip_address` (`inet`) | Client IP resolved by `TrustedProxyResolver`. **PII** — the other redaction target. |
| `occurredAt` | `occurred_at` (`nullable=false`) | `OffsetDateTime`. Written by the DB (`now()` in the INSERT SQL), so it reflects DB clock, not app clock. |
| `outcome` | `outcome` (`nullable=false`, len 16) | `@Enumerated(EnumType.STRING)` → `AuditOutcome`. |

### Gotchas
- `payload_json` is mapped as a `String`, not a structured type. `AuditWriter` serializes the payload map to a JSON string with Jackson and casts to `jsonb` in SQL (`?::jsonb`); the entity reads it back as the raw JSON string and hands that string to the DTO untouched. The UI parses it.
- Because the entity is `@Immutable`, the GDPR redaction cannot go through Hibernate dirty-checking — it *must* be a native UPDATE (see `AuditLogRepository.redactPiiForActor`).

---

## `audit/AuditWriter.java`

### Responsibility
The **single write component** for the trail. It owns the INSERT SQL, the fail-open vs. fail-closed transaction semantics, payload serialization, and null-safe JDBC binding. Everything that records an audit event goes through here.

### Why raw JDBC instead of the JPA repository?
The entity is `@Immutable`, so Hibernate won't persist it as a normal insert in the usual flow, and — more importantly — the writer needs precise control over Postgres-specific casts (`?::jsonb`, `?::inet`) and over the `null` SQL types for UUID columns (`Types.OTHER`). A `JdbcTemplate` gives that control cleanly.

```sql
INSERT INTO audit_log (id, actor_user_id, actor_org_id, action, target_type,
                       target_id, payload_json, ip_address, occurred_at, outcome)
VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::inet, now(), ?)
```

### Construction & the self-injection trick

```java
public AuditWriter(JdbcTemplate jdbc, @Lazy AuditWriter self) { ... }
```

`self` is a `@Lazy` self-reference to the **Spring proxy** of this bean. This is essential: the fail-open path uses `@Transactional(propagation = REQUIRES_NEW)`, and Spring's transactional advice is applied by the proxy. A plain `this.writeFailOpen(...)` self-invocation would bypass the proxy and the `REQUIRES_NEW` would silently do nothing. By calling `self.writeFailOpen(...)` the call goes back out through the proxy and the new transaction actually starts. `@Lazy` breaks the constructor self-dependency cycle.

### Public methods

**`void record(actorUserId, actorOrgId, action, targetType, targetId, payload, ip)`** — legacy 7-arg overload. A thin delegate that records a **fail-open `SUCCESS`** row. Kept so older call sites compile unchanged.

**`void record(actorUserId, actorOrgId, action, targetType, targetId, payload, ip, outcome, failClosed)`** — the canonical method. Logic:
1. If `action` is null/blank → return silently (a row with no action is useless and would violate `NOT NULL`).
2. Normalize `outcome` (null → `SUCCESS`).
3. Serialize the payload map to a JSON string (`serialize`).
4. **Branch on `failClosed`:**
   - `true` → call `insert(...)` **inline, in the caller's transaction**, no try/catch. Any failure propagates and rolls back the surrounding business transaction. This is *atomic coupling*: for high-value actions the rule is "if we can't prove we audited it, the action itself must not commit."
   - `false` → call `self.writeFailOpen(...)` which runs in a **separate `REQUIRES_NEW` transaction** and swallows exceptions. This is *best-effort forensics that survives a business rollback*: the audit row commits independently, so even if the business tx later rolls back, the attempt is recorded (e.g. a failed/denied action is captured even though the operation aborted).

| Mode | Transaction | On failure | Use for |
|------|-------------|-----------|---------|
| **fail-closed** (`failClosed=true`) | Inline, caller's tx | Propagates → business tx rolls back | High-value actions (configured via `app.audit.fail-closed-actions`) |
| **fail-open** (`failClosed=false`) | `REQUIRES_NEW`, separate tx | Logged + swallowed | Default; forensics that must survive a rollback |

**`@Transactional(propagation = REQUIRES_NEW) void writeFailOpen(...)`** — wraps `insert(...)` in try/catch and logs `error` on failure. Must be public and invoked through the proxy (hence `self`). Note it takes the *already-serialized* `payloadJson` string, not the map.

### Private helpers

**`String serialize(action, payload)`** — null/empty map → `null` (stored as SQL NULL `jsonb`). Otherwise Jackson `writeValueAsString`. On `JsonProcessingException`, logs a `warn` and returns `null` — i.e. serialization failure degrades to "no payload" rather than failing the whole audit write. Uses a privately-constructed `ObjectMapper` (no Spring config / module registration), so payload values should be plain JSON-friendly types.

**`insert(...)`** — the JDBC binding. Notable details:
- Generates a fresh id with `Ids.newId()` (so the app, not the DB default, owns the PK).
- For nullable UUIDs it uses `ps.setNull(i, Types.OTHER)` rather than `setObject(i, null)` — Postgres needs the explicit "OTHER" SQL type to bind a `null` UUID without a type-inference error.
- `occurred_at` is **not** bound — it's `now()` in the SQL, i.e. DB-clock authoritative.

### Collaborators
- **Calls:** `JdbcTemplate`, `com.example.cp.common.Ids`, Jackson `ObjectMapper`.
- **Called by:** `AuditInterceptor.emit` (the AOP path) and many explicit call sites — `AuthController`, `SsoService`/`SsoSuccessHandler`/`SsoProvisioningService`, `ScimService`, `BillingService`, `WebhookController`, `DataPrivacyController`, and `GlobalExceptionHandler` (the terminal fallback for thrown requests).

### Gotchas
- The writer **does not** read `AuditContext`; it takes everything as explicit arguments. The marshalling from `AuditContext` happens in `AuditInterceptor.emit` and in explicit call sites. Keeping the writer context-free makes it usable both inside and outside a web request.
- Choosing fail-closed is a *configuration* decision (`AuditProperties.failClosedActions`) consulted by the interceptor, OR an explicit boolean passed by a call site. Most explicit call sites pass `failClosed=false`.

---

## `audit/AuditInterceptor.java`

### Responsibility
An **AOP aspect** that auto-captures an audit row for every mutating controller endpoint, so auditing is the default rather than something each handler must remember. It also enriches the row with error metadata when the endpoint throws, classifies the outcome, and resolves the client IP.

### `@Aspect @Component public class AuditInterceptor`

### Pointcuts

```java
@Pointcut("within(com.example.cp..*Controller)")           // any class named *Controller
public void inControllerLayer() {}

@Pointcut("@annotation(PostMapping) || @annotation(PutMapping)
        || @annotation(PatchMapping) || @annotation(DeleteMapping)")
public void mutatingEndpoint() {}
```

Only **mutating** endpoints are advised. `@GetMapping` reads (including the audit search endpoints themselves) are intentionally **not** audited — otherwise reading the audit log would generate audit noise.

### Advice

**`@AfterReturning("inControllerLayer() && mutatingEndpoint()")  afterMutating(jp, ret)`**
- If `AuditContext.isRecorded()` → return (an explicit write already happened; don't duplicate).
- Otherwise `emit(jp, SUCCESS, null)`.
- `finally { AuditContext.clear() }` — always clears the ThreadLocal so the sentinel/state can't leak to the next request on the same pooled thread.

**`@AfterThrowing("inControllerLayer() && mutatingEndpoint()")  afterThrowing(jp, ex)`**
- Same `isRecorded()` short-circuit.
- Otherwise `emit(jp, outcomeFor(ex), ex)` — records a `DENIED`/`FAILED` row enriched with error details.
- `finally { AuditContext.clear() }`.
- **Crucially does not swallow `ex`** — `@AfterThrowing` lets the exception continue to propagate to `GlobalExceptionHandler`, which renders the HTTP error response (and is itself a final fallback audit writer for requests the aspect didn't cover).

### `private void emit(JoinPoint jp, AuditOutcome outcome, Throwable ex)`
The shared write path. Order of resolution (everything prefers `AuditContext`, falls back to request/security context):

1. `action`, `targetType`, `targetId`, `payload` ← from `AuditContext`.
2. `ip` ← `AuditContext.currentIp()`, else `proxyResolver.resolveClientIp(req)`.
3. If `action` is null/blank → `deriveAction(jp, req)` (e.g. `POST /api/v1/orgs`).
4. `userId`/`orgId` ← `AuditContext`; if `userId` null, fall back to `SecurityUtils.currentUser().userId()`.
5. Build a **defensive copy** of the payload (`new HashMap<>(payload)`), never mutating the ThreadLocal's map.
6. If `ex != null`, enrich the payload: `error.class` (simple name), `error.message` (truncated to 240 chars), and `http.status` if derivable. Truncation avoids logging secrets or huge stack text into `payload_json`.
7. `failClosed = failClosedActions.contains(action)` — config-driven escalation: if the resolved action is in the allowlist, the row is written fail-closed even though it came through the aspect.
8. `writer.record(userId, orgId, action, targetType, targetId, safePayload, ip, outcome, failClosed)`.

### Outcome / status classification helpers

**`AuditOutcome outcomeFor(Throwable ex)`** — `AccessDeniedException` / `AuthenticationException` → `DENIED`; `ApiException` with status 401/403 → `DENIED`; everything else → `FAILED`.

**`Integer httpStatusOf(Throwable ex)`** — `ApiException.getStatus().value()`, else 403 for `AccessDeniedException`, 401 for `AuthenticationException`, else `null`.

**`String truncate(String)`** — caps at `MAX_ERROR_MSG = 240` chars, appending `...`.

### Action derivation (when no explicit action was set)

**`deriveAction(jp, req)`** = `httpVerb(method) + " " + path` where `path` is the request URI, or `DeclaringType.methodName` if there's no request. **`httpVerb(Method)`** inspects the mapping annotation: `POST`/`PUT`/`PATCH`/`DELETE`/`REQUEST` (for `@RequestMapping`)/`ACTION` (fallback).

**`Optional<HttpServletRequest> currentRequest()`** — pulls the request from `RequestContextHolder`; returns empty (not throwing) if there's no servlet request bound (e.g. async/non-web invocation).

### Configuration read
Constructor takes `AuditProperties` and snapshots `props.getFailClosedActions()` into a `HashSet<String>` at startup. Null-safe: a missing `AuditProperties` or null list yields an empty set (everything fail-open).

### Collaborators
- **Calls:** `AuditContext` (read), `AuditWriter.record`, `TrustedProxyResolver.resolveClientIp`, `SecurityUtils.currentUser`, `ApiException`.
- **Called by:** Spring AOP, around every mutating controller method in `com.example.cp..*Controller`.

### Gotchas
- The `isRecorded()` sentinel is the *coordination contract* between this aspect and explicit writers. Any call site that writes its own row **must** call `AuditContext.markRecorded()` or it will get a duplicate row.
- The aspect clears `AuditContext` in `finally`. If `GlobalExceptionHandler` later wants the context for a thrown request, it must tolerate that the aspect may have already cleared it — which is exactly why the handler also falls back to the security principal for the actor.
- Pointcut is name-based (`*Controller`). A controller class not ending in `Controller` would silently escape auto-auditing.

---

## `audit/AuditLogRepository.java`

### Responsibility
The **Spring Data JPA repository** for reads + the one sanctioned mutation (GDPR redaction). Extends `JpaRepository<AuditLog, UUID>`. All query methods are **native SQL** for reasons described below.

### `int redactPiiForActor(@Param("actorId") UUID actorId)` — the GDPR exception

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query(value = "UPDATE audit_log "
        + "SET payload_json = '{\"redacted\":true,\"reason\":\"gdpr_erasure\"}'::jsonb, ip_address = NULL "
        + "WHERE actor_user_id = :actorId", nativeQuery = true)
int redactPiiForActor(UUID actorId);
```

This is the **only** code path that mutates an existing audit row. Key facts:
- It scrubs **only** the two PII-bearing free-form columns (`payload_json`, `ip_address`), replacing the payload with a small PII-free marker and nulling the IP. The identity/integrity columns (`id`, `actor_user_id`, `action`, `target_type`, `target_id`, `occurred_at`, `outcome`) are left intact so the security trail and its tamper-evidence survive.
- It deliberately bypasses the `@Immutable` entity mapping by being a native UPDATE. Hibernate would never emit an UPDATE for an `@Immutable` entity, so JPA dirty-checking is not an option here.
- `clearAutomatically/flushAutomatically = true` keep the persistence context consistent with the bulk native update (otherwise stale entities could linger in the L1 cache).
- It works **only inside an erasure transaction** that has set the session GUC `app.audit_redaction = 'on'` (done `SET LOCAL` by `ErasureService.redactSubjectAudit`). Without that GUC the DB trigger rejects the UPDATE. The trigger *also* re-verifies that no identity column changed — defense in depth in case a future query touched more than it should.
- Returns the number of rows redacted (used for logging in `ErasureService`).
- **Caller:** `ErasureService.redactSubjectAudit(userId)` only (single + tenant erasure paths).

### `SEARCH_FILTER` (shared SQL fragment)

```sql
  (CAST(:action AS text)     IS NULL OR action      = :action)
  AND (CAST(:actor AS uuid)  IS NULL OR actor_user_id = :actor)
  AND (CAST(:targetType AS text) IS NULL OR target_type = :targetType)
  AND (CAST(:targetId AS text)   IS NULL OR target_id   = :targetId)
  AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
  AND (CAST(:to   AS timestamptz) IS NULL OR occurred_at <  :to)
```

The explicit `CAST(:param AS type)` is **load-bearing**, not stylistic. With a bare nullable bind used as `:p IS NULL`, Postgres can't infer the parameter's type and throws *"could not determine data type of parameter"*. Casting tells Postgres the type so each filter is genuinely optional. Note the time window is **half-open** (`>= from`, `< to`) — the natural semantics for paging across day boundaries.

### `Page<AuditLog> search(action, actor, targetType, targetId, from, to, pageable)`
Global search across all tenants. `ORDER BY occurred_at DESC` is **hard-coded in the native SQL** with a separate `countQuery`. Because of this, callers **must pass an unsorted `Pageable`** — a native query can't translate a JPA `Sort` on the entity property to a column, and a `Sort` would also append a *second* `ORDER BY`, corrupting the SQL. (`AuditController` enforces this by passing `null` for the sort.)

### `Page<AuditLog> searchForOrg(orgId, action, actor, targetType, targetId, from, to, pageable)`
Same as `search`, but prepended with `actor_org_id = :orgId AND ...`. This is the **tenant-isolation boundary** for the org-scoped endpoint: rows are filtered to a single org's events.

### Collaborators
- **Called by:** `AuditController` (`search`, `searchForOrg`) and `ErasureService` (`redactPiiForActor`).

### Gotchas
- Passing a sorted `Pageable` into `search`/`searchForOrg` will break the generated SQL — always go through `PageRequestParams.of(page, size, null)`.
- The redaction marker JSON string is hard-coded; if its schema ever needs to change, both this method and any consumer parsing `redacted:true` must change together.

---

## `audit/AuditController.java`

### Responsibility
The **REST read API** for the audit trail. Two endpoints (global + org-scoped), each filtered/paginated, plus an inner DTO that shapes the entity for JSON.

### `@RestController @RequestMapping("/api/v1") public class AuditController`

### `GET /api/v1/audit` → `globalSearch(...)`
- `@PreAuthorize("hasAuthority('audit.read')")` — only principals holding the `audit.read` authority (a platform/super-admin-level permission) may read across all tenants.
- Optional filters: `action`, `actor` (UUID), `targetType`, `targetId`, `from`, `to` (both `@DateTimeFormat ISO.DATE_TIME` → `OffsetDateTime`), `page`, `size`.
- Builds an **unsorted** `Pageable` via `PageRequestParams.of(page, size, null)` (the DESC order is fixed in the query).
- Calls `repo.search(...)`, maps each `AuditLog` → `AuditLogDto`, and wraps in `PagedResponse.of(content, totalElements, number, size)`.

### `GET /api/v1/orgs/{orgId}/audit` → `orgSearch(...)`
- `@PreAuthorize("hasAuthority('audit.read') or @orgAccess.isOwnerOrAdmin(#orgId)")` — either a platform admin (`audit.read`) **or** an owner/admin of *that specific org*. This is the tenant-scoped variant: the SpEL `@orgAccess.isOwnerOrAdmin(#orgId)` is the membership check that lets an org's own admins read their slice of the trail without granting them the global authority.
- Defensive `if (orgId == null) throw ApiException.badRequest("orgId required")` (a path-variable UUID; the null guard is belt-and-suspenders).
- Calls `repo.searchForOrg(orgId, ...)` — the SQL enforces `actor_org_id = orgId`, so the DB does the tenant filtering rather than relying on post-filtering.

### `public record AuditLogDto(...)`
A flat projection of `AuditLog` (id, actorUserId, actorOrgId, action, targetType, targetId, payloadJson, ipAddress, occurredAt, outcome). `static AuditLogDto from(AuditLog a)` copies all fields. `payloadJson` is passed through as the raw JSON string for the UI to parse.

### Collaborators
- **Calls:** `AuditLogRepository`, `PageRequestParams`, `PagedResponse`, `ApiException`, the `@orgAccess` security bean.
- **Called by:** the admin UI's audit views.

### Gotchas
- `ipAddress` and `payloadJson` (potential PII) are returned in the API response — access is gated by `audit.read` / org admin, but the data is *not* masked at read time. PII removal happens only at erasure (redaction), not at read.
- These are `@GetMapping` endpoints, so they are intentionally **outside** the `AuditInterceptor` mutating-endpoint pointcut — reading the audit log does not itself produce audit rows.

---

## Supporting collaborators (in `com.example.cp.common`)

These two classes live in `common` but are integral to the audit subsystem.

### `common/AuditContext.java` — the per-request ThreadLocal

A `final` utility holding a `ThreadLocal<Ctx>` (`withInitial(Ctx::new)`) that lets explicit call sites *stage* audit data that the `AuditInterceptor` (or `GlobalExceptionHandler`) later reads and writes. Fields in the inner `Ctx`: `action`, `targetType`, `targetId`, `actorUserId`, `actorOrgId`, `ip`, `outcome` (default `SUCCESS`), `failClosed` (default `false`), `recorded` (default `false`), and a `payload` map.

| Setter | Reader | Purpose |
|--------|--------|---------|
| `set(action)` | `currentAction()` | The dotted action code for this request. |
| `setTarget(type, id)` | `currentTargetType()/currentTargetId()` | What was acted on. |
| `putPayload(k, v)` | `currentPayload()` | Accumulate context (the map persists across calls in one request). |
| `setActor(userId, orgId)` | `currentActorUserId()/currentActorOrgId()` | Override actor (e.g. before identity is in the security context). |
| `setIp(ip)` | `currentIp()` | Override the resolved IP. |
| `setOutcome(o)` | `currentOutcome()` | Stage a non-default outcome (null → `SUCCESS`). |
| `markFailClosed()` | `isFailClosed()` | Request fail-closed semantics. |
| `markRecorded()` | `isRecorded()` | **Sentinel**: an explicit write already happened — aspect/handler must not duplicate. |
| — | `clear()` | `CTX.remove()` — drop all state for this thread. |

**Why it matters / gotchas:**
- The **`recorded` sentinel** is the contract that prevents double-writing. Explicit writers call `markRecorded()`; the aspect checks `isRecorded()` first.
- It's a **ThreadLocal on a pooled (servlet) thread**, so it *must* be cleared at end of request or state leaks into the next request. `AuditInterceptor` clears it in `finally`; `GlobalExceptionHandler` clears it as the terminal step for failed requests. This is a real correctness/security hazard if a new code path forgets to clear.
- The `payload` map is shared and mutable; `AuditInterceptor.emit` defensively copies it before adding error fields, so it never corrupts staged state.

### `common/AuditProperties.java` — `@ConfigurationProperties("app.audit")`

Self-registered as a `@Component` (so no `@EnableConfigurationProperties` needed). Two lists, both null-coalesced to empty in their setters:

| Property | Default | Consumer | Effect |
|----------|---------|----------|--------|
| `app.audit.trusted-proxies` | `[]` | `TrustedProxyResolver` | CIDR blocks whose `X-Forwarded-For` is trusted. Empty ⇒ never trust XFF; always use the direct socket peer. |
| `app.audit.fail-closed-actions` | `[]` | `AuditInterceptor` (and high-value service call sites) | Action codes whose audit write must be transactional/fail-closed. Empty ⇒ everything fail-open. |

**Why it matters:** these two config knobs let operators tune the audit subsystem's two security-sensitive behaviors (IP trust and write durability) without code changes. An empty `fail-closed-actions` list means *every* auto-captured row is best-effort.

### `common/TrustedProxyResolver.java` (brief — primary owner is the `common` docs)

Resolves the real client IP: returns the direct peer (`getRemoteAddr()`) unless the peer is inside a configured trusted-proxy CIDR, in which case it returns the **leftmost** token of `X-Forwarded-For`. Compiles CIDRs once at construction (skipping/warn-logging invalid entries). Used by `AuditInterceptor.emit` so the `ip_address` stored in `audit_log` is non-spoofable when XFF is untrusted. Falls back to the peer on malformed XFF (never trusts garbage). Returns `null` only when the request is `null`.

---

## The immutability trigger & the GDPR-redaction exception (DB layer)

The append-only guarantee is enforced **in the database**, not the app — an ORM annotation can't stop a rogue `UPDATE`/`DELETE`. The trigger is defined in `08-audit.sql` and later widened in `18-webhook-fanout-integrity.sql`.

### Original (`08-audit.sql`, changeset `cp:08-audit-log-immutability`)
A `BEFORE UPDATE` and a `BEFORE DELETE` trigger, both calling `audit_log_block_modifications()`, which simply `RAISE EXCEPTION 'audit_log is immutable: % operation is not permitted'`. Initially **all** mutation is forbidden. The same migration also creates the table and three read indexes (`occurred_at`; `(actor_user_id, occurred_at)`; `(target_type, target_id)`).

### Outcome column (`13-audit-outcome.sql`)
Adds `outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'` with `CHECK (outcome IN ('SUCCESS','DENIED','FAILED'))` and an index `(action, outcome, occurred_at)`. This file also documents the intended **least-privilege production posture**: the app should connect as a role with only `INSERT/SELECT` on `audit_log` (no `UPDATE/DELETE/TRUNCATE`, no table ownership), with migrations run by a separate privileged role — because an owner-modifiable trigger is not, by itself, a sufficient tamper control. (Not enforced in the single-role dev/test setup.)

### The GDPR widening (`18-webhook-fanout-integrity.sql`, changeset `cp:18-audit-log-allow-gdpr-redaction`)

The trigger function is `CREATE OR REPLACE`d to permit exactly one kind of mutation:

```plpgsql
CREATE OR REPLACE FUNCTION audit_log_block_modifications() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log is immutable: DELETE operation is not permitted';
    END IF;
    -- UPDATE path: only a flagged GDPR redaction transaction may proceed.
    IF current_setting('app.audit_redaction', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'audit_log is immutable: UPDATE operation is not permitted';
    END IF;
    IF NEW.id           IS DISTINCT FROM OLD.id
       OR NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
       OR NEW.actor_org_id  IS DISTINCT FROM OLD.actor_org_id
       OR NEW.action        IS DISTINCT FROM OLD.action
       OR NEW.target_type   IS DISTINCT FROM OLD.target_type
       OR NEW.target_id     IS DISTINCT FROM OLD.target_id
       OR NEW.occurred_at   IS DISTINCT FROM OLD.occurred_at
       OR NEW.outcome       IS DISTINCT FROM OLD.outcome THEN
        RAISE EXCEPTION 'audit_log redaction may only scrub payload_json/ip_address, not identity columns';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

Three layers of protection on a redaction:
1. **`DELETE` is categorically forbidden** — the trail can never lose a row.
2. **`UPDATE` requires opt-in** — only a transaction that set `app.audit_redaction = 'on'` may update. `current_setting(..., true)` returns NULL (not an error) when the GUC is unset, and `IS DISTINCT FROM 'on'` treats that as "not opted in."
3. **Even opted-in, only PII columns may change** — any change to an identity/integrity column raises. So redaction can scrub `payload_json`/`ip_address` and nothing else.

### End-to-end redaction flow

```
ErasureService.eraseUser(userId)               [@Transactional]
   ├─ pseudonymise users row (email/full_name/password_hash, status=DELETED)
   ├─ bump token_version (revoke sessions) + best-effort cache write
   ├─ delete sso_identities + MFA enrollment
   ├─ redactSubjectAudit(userId):
   │     SET LOCAL app.audit_redaction = 'on'     -- scoped to THIS tx, auto-reset on commit/rollback
   │     auditLogRepository.redactPiiForActor(userId)
   │         -> native UPDATE audit_log SET payload_json='{"redacted":true,...}', ip_address=NULL
   │            WHERE actor_user_id = :userId
   │            -> BEFORE UPDATE trigger: GUC='on' ✓, identity cols unchanged ✓ -> allowed
   └─ write erasure_log ledger row (PII-free)
```

**Why `SET LOCAL`:** the GUC is bound to the current transaction and the same transaction-bound JDBC connection that runs the UPDATE; it is automatically reset at commit/rollback, so the redaction exception can never "leak" past the erasure transaction and accidentally license other UPDATEs. The actor FK (`actor_user_id`) is intentionally retained and points at the now-pseudonymised `users` row — that's what "pseudonymises the actor" while keeping the trail's referential integrity.

### Gotchas for a new engineer
- The trail's tamper-evidence ultimately depends on the app **not** running as the table owner in production (an owner can `DROP`/replace the trigger). The trigger is a *guard*, the role separation is the *control*; `13-audit-outcome.sql`'s header spells this out.
- If you ever need to fix data in `audit_log`, there is no supported path other than redaction-shaped PII scrubbing inside an `app.audit_redaction='on'` transaction. `DELETE` and identity-column edits are impossible by design.
- The redaction marker payload and the trigger's identity-column list are coupled to `AuditLogRepository.redactPiiForActor` and the `AuditLog` mapping respectively — change one, review all three.
