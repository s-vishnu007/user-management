# `com.example.cp.events` — Transactional Outbox, Event Stream & LISTEN/NOTIFY

## Module overview

This package is the **delivery half of the control-panel's transactional outbox**. Domain services (subscriptions, licensing, key rotation, etc.) never call a message broker directly; instead they `INSERT` a row into the `outbox_events` table inside the same database transaction that mutates business state. That gives the classic outbox guarantee: a domain event is committed **atomically** with the state change that produced it — there is no window where one persists and the other is lost. This package then takes those committed rows and (a) durably **delivers** them to Postgres `NOTIFY` subscribers via a multi-instance-safe scheduler, (b) exposes them as a paginated, RBAC-gated **read feed** (`GET /api/v1/events`), (c) optionally **tails** the `NOTIFY` stream for diagnostics, and (d) **purges** terminal rows so the table stays bounded.

A crucial split to keep straight from the very first read:

| Role | Class | Package | What it does |
|------|-------|---------|--------------|
| **Enqueue** (write side) | `OutboxPublisher` | `com.example.cp.subscriptions` | `INSERT` an `outbox_events` row inside a domain transaction |
| **Deliver** (NOTIFY side) | `OutboxDeliveryScheduler` | **`com.example.cp.events`** (this package) | claim PENDING rows, `pg_notify`, mark PUBLISHED, retry/poison, purge |
| **Read feed** | `EventStreamController` + `OutboxEventRepository` | this package | paginated REST view of the table |
| **Diagnostic tail** | `EventListener` | this package | optional `LISTEN cp_events` logger |

There are **two independent consumers** of the same `outbox_events` table, and they use **two different cursors** that must not be confused:

- The **NOTIFY publisher** (this package's `OutboxDeliveryScheduler`) drives off the `status` column (`PENDING → PUBLISHED/FAILED`).
- The **webhook fan-out** (`com.example.cp.webhooks.WebhookDispatchScheduler`) drives off a **separate** `fanned_out_at` marker column it owns. It never touches `status`, and the publisher never touches `fanned_out_at`. The retention purge in this package is deliberately written to respect *both* cursors so neither consumer can lose an event.

### How it fits the bigger picture

```
 Domain service (subscription/license/key)        <- writes business rows + outbox row
        │  (same JPA / JDBC transaction)
        ▼
   outbox_events  (durable queue table, JSONB payload)
        │                                  │
        │ status cursor                    │ fanned_out_at cursor
        ▼                                  ▼
 OutboxDeliveryScheduler            WebhookDispatchScheduler
  (this package)                     (webhooks package)
   pg_notify(cp_events)              per-subscription signed HTTP POSTs
        │                                  
        ▼                                  
  ┌──────────────┐   ┌──────────────────────────────┐
  │ EventListener│   │ GET /api/v1/events (REST feed)│
  │ (diag tail)  │   │  EventStreamController + repo │
  └──────────────┘   └──────────────────────────────┘
        ▲
   purgeOldEvents() deletes terminal rows older than retention
   (only when status terminal AND fanned_out_at set)
```

`@Scheduled` is globally enabled by `@EnableScheduling` on `com.example.cp.ControlPanelApplication`, so both scheduled methods in `OutboxDeliveryScheduler` run automatically when the app boots. The `EventListener` is **off by default** and must be explicitly enabled.

---

## File: `OutboxEvent.java`

`control-panel-api/src/main/java/com/example/cp/events/OutboxEvent.java`

### Responsibility
The JPA `@Entity` mapping the `outbox_events` table. It is the **read model** for the REST feed and the schema-of-record that `spring.jpa.hibernate.ddl-auto=validate` checks the Liquibase-managed table against. Note the asymmetry: the *write* path (`subscriptions.OutboxPublisher`) and the *delivery* path (`OutboxDeliveryScheduler`) both bypass this entity and use raw JDBC; this entity is read through Hibernate only by `OutboxEventRepository.findSince(...)`.

### Type: `OutboxEvent` (class)
Lombok-generated `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`. `@Builder.Default` is applied to `attempts` and `status` so the builder honours the field initializers (a known Lombok gotcha — without `@Builder.Default` the builder would reset them to `0`/`null`).

Fields and their column mapping:

| Field | Column | Notes |
|-------|--------|-------|
| `UUID id` | `id` (PK) | DB default `gen_random_uuid()`; the entity does not generate it (writes go through JDBC `INSERT` which relies on the DB default). |
| `String aggregateType` | `aggregate_type VARCHAR(64)` | e.g. `"subscription"`, `"license_token"`. Used by webhook fan-out to resolve the owning org. |
| `String aggregateId` | `aggregate_id VARCHAR(128)` | the subscription id, or the license `jti`, etc. |
| `String eventType` | `event_type VARCHAR(128)` | e.g. `"subscription.created"`. |
| `String payloadJson` | `payload_json jsonb` | `@JdbcTypeCode(SqlTypes.JSON)` maps a Java `String` to a Postgres `jsonb` column. The string *is* the JSON document. |
| `OffsetDateTime occurredAt` | `occurred_at` | DB default `now()`; the canonical ordering key for delivery, the read feed, and fan-out. |
| `OffsetDateTime publishedAt` | `published_at` | set when the NOTIFY succeeds; `null` while pending. |
| `int attempts` | `attempts NOT NULL DEFAULT 0` | incremented on each delivery failure. |
| `OffsetDateTime nextAttemptAt` | `next_attempt_at` | backoff gate; `null` = immediately eligible. |
| `String lastError` | `last_error TEXT` | truncated failure diagnostic. |
| `Status status` | `status VARCHAR(16) NOT NULL` | `@Enumerated(EnumType.STRING)` — stored as the enum *name*, not its ordinal (important: a CHECK constraint `chk_outbox_status` enforces `IN ('PENDING','PUBLISHED','FAILED')`). |

### Nested enum: `OutboxEvent.Status`
`PENDING`, `PUBLISHED`, `FAILED`. The lifecycle:

```
            notify OK
 PENDING ─────────────────► PUBLISHED   (terminal, never re-claimed)
   │  ▲
   │  └── failure (attempts < MAX): attempts++, next_attempt_at = now + backoff, stays PENDING
   │
   └────── failure (attempts >= MAX) ──► FAILED   (poison / quarantine, terminal)
```

- **PENDING** — awaiting or retrying delivery; claimable once `next_attempt_at` is null or in the past.
- **PUBLISHED** — `NOTIFY` succeeded and committed; `published_at` set; never re-claimed.
- **FAILED** — poison message: exceeded `MAX_ATTEMPTS`; quarantined for operator inspection; never auto-retried.

### Collaborators & gotchas
- Read by `OutboxEventRepository` / `EventStreamController`.
- The legacy migration `11-outbox.sql` created the table with only `id/aggregate_*/event_type/payload_json/occurred_at/published_at`. `14-outbox-reliability.sql` added `attempts/next_attempt_at/last_error/status` and **backfilled** existing delivered rows (`published_at IS NOT NULL`) to `status = 'PUBLISHED'` so the new status-driven poller would not re-deliver historical events. `18-webhook-fanout-integrity.sql` added `fanned_out_at` — note this column is **not** mapped on this entity (it is owned by the webhooks package and read only via raw SQL). Because `ddl-auto=validate`, every mapped field here must exactly match a column or boot fails.

---

## File: `OutboxEventRepository.java`

`control-panel-api/src/main/java/com/example/cp/events/OutboxEventRepository.java`

### Responsibility
Spring Data JPA repository for `OutboxEvent`, used **only** by the read feed. It exposes the standard `JpaRepository<OutboxEvent, UUID>` CRUD plus one custom query.

### Method: `List<OutboxEvent> findSince(OffsetDateTime since, Pageable pageable)`
A **native** query:

```sql
SELECT * FROM outbox_events
WHERE (CAST(:since AS timestamptz) IS NULL OR occurred_at >= :since)
ORDER BY occurred_at ASC
```

- **Why native + the explicit `CAST`?** A bare nullable bind parameter used as `:since IS NULL` gives Postgres no type to infer and it fails with *"could not determine data type of parameter $1"*. `CAST(:since AS timestamptz)` pins the type so the same query works whether `since` is supplied or null. This is the single most surprising thing about this file — do not "simplify" the cast away.
- **Why the `since IS NULL OR ...` pattern?** It makes the `since` filter optional in one query: pass `null` to get the whole (paginated) stream from the start, or a timestamp to tail forward from a cursor.
- **Pagination is load-bearing.** Spring Data appends `LIMIT`/`OFFSET` from the `Pageable`, so this can never pull the entire table in one call. The `ORDER BY occurred_at ASC` lives **inside the SQL** because Spring Data does **not** inject a `Sort` into native queries — therefore the controller passes an *unsorted* `PageRequest` on purpose (adding a sort there would be silently ignored, or worse, produce invalid SQL).
- **Page-size cap** is enforced upstream in the controller (see `PageRequestParams.MAX_SIZE`), not here.

### Collaborators
- Called by `EventStreamController.list(...)`.
- Backed by table `outbox_events`; the `idx_outbox_events_occurred_at` b-tree index (from `18-webhook-fanout-integrity.sql`) supports the `ORDER BY occurred_at` range scan.

---

## File: `EventStreamController.java`

`control-panel-api/src/main/java/com/example/cp/events/EventStreamController.java`

### Responsibility
The REST endpoint `GET /api/v1/events` — a read-only, RBAC-gated, server-paginated view of the outbox. Intended for the admin UI / operators to inspect the event stream; it is **not** the durable delivery channel (that is NOTIFY / webhooks).

### Type: `EventStreamController` (`@RestController`, `@RequestMapping("/api/v1/events")`)
Constructor-injected with `OutboxEventRepository`.

### Method: `List<EventDto> list(OffsetDateTime since, Integer page, Integer size)`
- **Mapping:** `@GetMapping` on the class base path.
- **Authorization:** `@PreAuthorize("hasAuthority('event.read')")` — only principals granted the `event.read` authority may call it. This is method-level security; the gate is the `event.read` permission, not a role name.
- **Params:**
  - `since` — optional, ISO-8601 date-time (`@DateTimeFormat(iso = DATE_TIME)`), parsed to `OffsetDateTime`; forwarded to `findSince` as the lower bound.
  - `page` — optional 0-based page index. Clamped: `page == null || page < 0 → 0`.
  - `size` — optional page size. Clamped: `null` or `<= 0 → PageRequestParams.DEFAULT_SIZE` (20); otherwise `min(size, PageRequestParams.MAX_SIZE)` (200).
- **Flow:**
  1. clamp `page`/`size`;
  2. build an **unsorted** `PageRequest.of(p, s)` (the SQL carries its own ORDER BY — see repository note);
  3. `repo.findSince(since, pageable)`;
  4. map each `OutboxEvent` to an `EventDto`.

  ```
  request params ─► clamp ─► PageRequest(p, s) ─► findSince(since, pageable) ─► map(EventDto::from) ─► JSON array
  ```
- **Why clamp here instead of letting Spring resolve a `Pageable`?** Defensive bounding: a single feed call can never request an unbounded/huge page, which would let a caller pull the whole table and DoS the DB. The clamp logic duplicates `PageRequestParams.of(...)` but inlines it because this controller builds an *unsorted* `PageRequest` (it intentionally ignores `PageRequestParams.parseSort`), so it reuses only the constants.
- **Edge cases:** a malformed `since` yields a 400 from the `@DateTimeFormat` binding before the method runs. Negative `page` and absurd `size` are silently normalized rather than rejected.

### Nested record: `EventStreamController.EventDto`
`record EventDto(UUID id, String aggregateType, String aggregateId, String eventType, String payloadJson, OffsetDateTime occurredAt, OffsetDateTime publishedAt)`.

- The serialization shape returned to clients. Note it deliberately **omits** the operational columns `attempts`, `nextAttemptAt`, `lastError`, and `status` — the feed shows the *event*, not its delivery bookkeeping.
- `payloadJson` is emitted as a JSON **string** field (it is the raw `jsonb` text from the column), not as a nested object. Consumers must `JSON.parse` it client-side.
- `static EventDto from(OutboxEvent e)` — straightforward field copy.

### Collaborators
- Depends on `OutboxEventRepository` and the `com.example.cp.common.PageRequestParams` constants.
- Security wiring (the `event.read` authority) comes from the app's RBAC/`SecurityFilterChain` configuration (outside this package).

---

## File: `OutboxDeliveryScheduler.java`

`control-panel-api/src/main/java/com/example/cp/events/OutboxDeliveryScheduler.java`

### Responsibility
The **heart of this package**: a multi-instance-safe scheduler that (1) delivers committed outbox rows to Postgres `NOTIFY` subscribers and (2) purges terminal rows for retention. It works entirely through `JdbcTemplate` (no JPA), because the claim/update logic needs precise control over locking and SQL that JPA does not express cleanly.

> **Naming history (important for grepping):** this class was renamed from `OutboxPublisher` because that unqualified name collided with `com.example.cp.subscriptions.OutboxPublisher` (the *enqueue* side). The bean is explicitly named `@Component("eventsOutboxDeliveryScheduler")` to disambiguate. A dead, never-wired `OutboxRecorder` was removed during the same cleanup — so if older docs/notes mention an `OutboxRecorder`, it no longer exists.

### Type: `OutboxDeliveryScheduler` (`@Component("eventsOutboxDeliveryScheduler")`)

#### Constants
| Constant | Value | Purpose |
|----------|-------|---------|
| `BATCH_SIZE` | `100` | max rows claimed per delivery tick — bounds work and the held-lock set. |
| `CHANNEL` | `"cp_events"` | the `pg_notify` channel name (matches `EventListener` and the webhook docs). |
| `MAX_ATTEMPTS` | `10` (package-private) | after this many failures a row becomes `FAILED`/poison. |
| `BACKOFF_BASE` | `5s` (package-private) | base for capped exponential backoff. |
| `BACKOFF_CAP` | `1h` (package-private) | max retry delay. |
| `MAX_ERROR_LEN` | `2000` | `last_error` truncation bound so a giant exception cannot bloat the row. |

`MAX_ATTEMPTS`/`BACKOFF_*` are package-private specifically so `OutboxDeliverySchedulerTest` can assert against them.

#### Fields
- `JdbcTemplate jdbc` — injected; the sole DB collaborator.
- `ObjectMapper mapper = new ObjectMapper()` — used only to build the small NOTIFY payload JSON.
- `Duration retention` — from `@Value("${app.outbox.retention:P30D}")`, default 30 days. Drives `purgeOldEvents()`.

#### Constructor
`OutboxDeliveryScheduler(JdbcTemplate jdbc, @Value("${app.outbox.retention:P30D}") Duration retention)` — Spring binds the ISO-8601 `Duration` (`P30D`) from config; tests construct it directly with `Duration.ofDays(30)`.

---

### Method: `publishBatch()` — `@Scheduled(fixedDelay = 5000L)` `@Transactional`

Runs every 5 s (5 s **after the previous run finishes**, since it's `fixedDelay`). The whole method is one transaction, which is what makes `FOR UPDATE SKIP LOCKED` work across instances.

**Step 1 — claim due rows (multi-instance safe):**
```sql
SELECT id, aggregate_type, aggregate_id, event_type, attempts FROM outbox_events
WHERE status = 'PENDING'
  AND (next_attempt_at IS NULL OR next_attempt_at <= now())
ORDER BY occurred_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED
```
- `FOR UPDATE` locks each selected row **for the lifetime of the transaction**. `SKIP LOCKED` makes a sibling instance running the identical query simply *skip* the rows this instance already grabbed instead of blocking on them. Result: **no two app instances ever publish the same event, and no instance blocks waiting on another's locked rows.** This is the core of horizontal-scale safety (addressed ROADMAP gap #29).
- The due-time guard (`next_attempt_at IS NULL OR next_attempt_at <= now()`) skips rows currently in backoff.
- `ORDER BY occurred_at ASC` preserves causal-ish ordering within a claim.
- The partial index `idx_outbox_events_claimable ON (next_attempt_at, occurred_at) WHERE status='PENDING'` (from `14-outbox-reliability.sql`) keeps this claim query cheap by indexing only the work-to-do rows.
- **Gotcha — `LIMIT 100` is string-concatenated** (`"LIMIT " + BATCH_SIZE`). It is safe only because `BATCH_SIZE` is a private `int` constant, never user input. Do not parameterize it with caller data.

**Step 2 — per-row deliver, isolated:**
```java
for (ClaimedRow row : rows) {
    try { notify(row); markPublished(row, now); }
    catch (Exception ex) { markFailure(row, now, ex); }
}
```
Each row is wrapped in its own try/catch so **one poison row never aborts the rest of the batch** (covered by the `oneBadRow_doesNotAbortTheRest` test). Note: because the failure handler issues its own `UPDATE` and the exception is swallowed, the surrounding transaction stays alive and commits all the per-row updates atomically with the NOTIFYs.

**Step 3 — whole-batch failure:** the outer try/catch logs (`log.error`) and returns, letting the next tick retry. This covers claim-query/connection-level failures (not per-row delivery failures).

---

### Method: `notify(ClaimedRow row)` (private)

Builds a small JSON envelope and fires the notification:
```java
ObjectNode payload = mapper.createObjectNode();
payload.put("eventId", ...); payload.put("eventType", ...);
payload.put("aggregateType", ...); payload.put("aggregateId", ...);
jdbc.query("SELECT pg_notify(?, ?)", (ResultSetExtractor<Void>) rs -> null, CHANNEL, json);
```
Several deliberate decisions packed in here:
- **Parameterized `pg_notify(?, ?)`** — channel and payload are bind parameters, not string-interpolated SQL. No injection surface, no manual quote-escaping of the JSON.
- **Why `jdbc.query(...)` and not `jdbc.update(...)`?** `SELECT pg_notify(...)` returns a (void) **result row**. `jdbc.update()` would throw *"a result was returned when none was expected."* So it runs as a query with a `ResultSetExtractor<Void>` that discards the result.
- **Atomicity with NOTIFY buffering:** Postgres buffers `NOTIFY` until the transaction **commits**. Because `notify()` and `markPublished()` run in the *same* `@Transactional` method, the notification and the `status='PUBLISHED'` update are atomic — either both happen or neither does. A consumer can never see a NOTIFY for a row that wasn't marked published (and vice versa). The payload only carries identifiers; consumers re-read the full event from the table (or the REST feed) if they need the payload — keeping the NOTIFY message well under Postgres's 8 KB payload limit.

---

### Method: `markPublished(ClaimedRow row, OffsetDateTime now)` (private)
```sql
UPDATE outbox_events SET status='PUBLISHED', published_at=?, next_attempt_at=NULL, last_error=NULL WHERE id=?
```
Clears the retry bookkeeping and sets the terminal success state. Once `PUBLISHED`, the claim query's `status='PENDING'` filter guarantees the row is never re-claimed.

---

### Method: `markFailure(ClaimedRow row, OffsetDateTime now, Exception ex)` (private)

```
attempts = row.attempts + 1
error    = truncate(ex.getMessage() ?? ex.getClass().getName())   // bounded to 2000 chars
if attempts >= MAX_ATTEMPTS (10):
    UPDATE ... SET status='FAILED', attempts=?, last_error=?, next_attempt_at=NULL   // poison/quarantine + log.error
else:
    next = now + backoff(attempts)
    UPDATE ... SET attempts=?, last_error=?, next_attempt_at=?   // stays PENDING + log.warn
```
- **Poison handling:** at the 10th failure the row is quarantined as `FAILED` (so it stops consuming poller capacity forever) and logged at `error` level for operator attention. It is never auto-retried; an operator must intervene.
- **Transient handling:** below the cap, `attempts` is bumped, the error recorded, and `next_attempt_at` set to `now + backoff` so the due-time guard holds the row out of the next claims until the backoff elapses. The row remains `PENDING`.
- `ex.getMessage()` may be null (e.g. NPE) — the code falls back to the exception class name so `last_error` is never null on failure.

---

### Method: `backoff(int attempts)` (package-private static)

Capped exponential backoff: `BACKOFF_BASE * 2^(attempts-1)`, never exceeding `BACKOFF_CAP`.
```java
int shift = Math.max(0, attempts - 1);
if (shift >= 62) return BACKOFF_CAP;               // guard: 1L<<63 would overflow / go negative
long millis = BACKOFF_BASE.toMillis() << shift;
if (millis <= 0 || millis > BACKOFF_CAP.toMillis()) return BACKOFF_CAP;
return Duration.ofMillis(millis);
```
- The `shift >= 62` and `millis <= 0` guards protect against `long` overflow from the left shift before the `MAX_ATTEMPTS` cap would ever stop the row. With `MAX_ATTEMPTS = 10` the practical max delay is reached well before overflow, but the guard makes the function safe for any input (tested with `backoff(1000)`).
- Example schedule (base 5 s): attempt 1 → 5 s, 2 → 10 s, 3 → 20 s, 4 → 40 s, … saturating at 1 h.

---

### Method: `purgeOldEvents()` — `@Scheduled(fixedDelayString="${app.outbox.purge.fixed-delay:PT1H}", initialDelayString="${app.outbox.purge.initial-delay:PT5M}")` `@Transactional`

The **retention sweep**. Runs hourly by default, first run 5 minutes after boot (the initial delay keeps purge from competing with startup work).
```sql
DELETE FROM outbox_events
WHERE status IN ('PUBLISHED', 'FAILED')
  AND fanned_out_at IS NOT NULL
  AND occurred_at < ?            -- cutoff = now() - retention
```
Three guards, each protecting a different consumer:
1. **`status IN ('PUBLISHED','FAILED')`** — only terminal rows are deletable. A `PENDING` row is still owed a NOTIFY and must never be purged.
2. **`fanned_out_at IS NOT NULL`** — only rows the **webhook fan-out has already considered** are eligible. This is the cross-consumer safety interlock: the NOTIFY publisher could mark a row `PUBLISHED` long before the webhook fan-out has scanned it; deleting on `status` alone would let retention silently drop an event out from under the at-least-once webhook fan-out. Requiring *both* terminal `status` **and** a stamped `fanned_out_at` means an event is purged only after *both* consumers are done with it.
3. **`occurred_at < cutoff`** — only rows older than the retention window.

If a webhook fan-out never runs (e.g. disabled) those rows keep `fanned_out_at = NULL` and would accumulate forever — a known operational caveat, but the safe failure mode (retain rather than lose). Errors are caught and logged; the next tick retries.

---

### Nested record: `OutboxDeliveryScheduler.ClaimedRow` (private)
`record ClaimedRow(UUID id, String aggregateType, String aggregateId, String eventType, int attempts)` — the projection of the claim query; only the columns the delivery path needs. (It does **not** carry `payload_json`, because the NOTIFY envelope only sends identifiers.)

### Collaborators & gotchas
- **`@Transactional` + `@Scheduled` self-invocation caveat:** because `@Scheduled` invokes the method directly on the proxied bean (Spring's scheduler holds a reference to the proxy and calls the annotated method on it), the transactional advice *does* apply here — unlike the manual self-proxy gymnastics the `WebhookDispatchScheduler` needs for its phase methods. Just don't add an internal helper that calls `publishBatch()`/`purgeOldEvents()` from within the same bean, or it would bypass the transaction and run the claim in autocommit (releasing locks immediately → duplicate publishes).
- Tested hermetically by `OutboxDeliverySchedulerTest` (mocked `JdbcTemplate`, no DB) which pins the claim-query shape, success/retry/poison transitions, the purge predicate, and the backoff math.
- Reads config `app.outbox.retention`, `app.outbox.purge.fixed-delay`, `app.outbox.purge.initial-delay`.

---

## File: `EventListener.java`

`control-panel-api/src/main/java/com/example/cp/events/EventListener.java`

### Responsibility
An **optional, diagnostic** Postgres `LISTEN cp_events` consumer. It is **disabled by default** (`@ConditionalOnProperty(prefix="app.events.listener", name="enabled", havingValue="true")`) and, when enabled, only **logs** received notifications. It is explicitly **not** the durable delivery path — the durable path is `OutboxDeliveryScheduler` writing `pg_notify` (and the webhook fan-out). Think of this as `tail -f` on the NOTIFY stream for an operator who wants to watch events flow.

### Type: `EventListener` (`@Component`, conditionally registered)

#### Constants / fields
| Member | Value / source | Purpose |
|--------|----------------|---------|
| `BACKOFF_BASE_MS` | `1_000` | reconnect backoff base. |
| `BACKOFF_CAP_MS` | `60_000` | reconnect backoff cap (1 min). |
| `DataSource dataSource` | injected | source of the **dedicated** JDBC connection. |
| `AtomicBoolean running` | — | lifecycle flag; flipped to `false` on shutdown to break the loops. |
| `Thread thread` | — | the daemon worker thread. |
| `volatile Connection conn` | — | the dedicated long-lived LISTEN connection (`volatile` for cross-thread visibility / safe close). |
| `long pollMs` | `@Value("${app.events.listener.poll-ms:1000}")` | how long each `getNotifications` poll blocks. |

### Method: `start()` — `@PostConstruct`
Sets `running = true`, spawns a **daemon** thread named `"outbox-listener"` running `run()`, logs startup. Daemon so it never blocks JVM shutdown.

### Method: `run()` (private) — outer supervision loop
```
while (running):
    try   { openAndListen(); failures = 0; consume(); }   // consume() blocks until conn breaks or shutdown
    catch { if !running break; failures++; log.warn(...) }
    finally { closeQuietly(); }
    if running: sleepBackoff(failures)
```
- **Self-healing:** if the connection drops or the initial connect fails, the loop does **not** die — it logs, closes the bad connection, backs off, and reconnects (re-issuing `LISTEN`). `failures` resets to 0 on a healthy connect so backoff starts fresh after recovery. It only exits when `running` is false (shutdown).

### Method: `openAndListen()` (private)
Grabs a **dedicated** connection straight from the `DataSource` (`dataSource.getConnection()`) and runs `LISTEN cp_events`.
- **Why a dedicated connection, not a pooled borrow?** A `LISTEN` connection must stay open and blocking for the app's lifetime. Pinning a HikariCP pool connection that long would starve the pool. So it owns its connection outright and closes it on reconnect/shutdown.

### Method: `consume()` (private) — inner poll loop
Uses **reflection** to avoid a hard compile-time dependency on the PG driver classes:
```java
Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
Object pg = conn.unwrap(pgConnClass);
Method getNotifications = pgConnClass.getMethod("getNotifications", int.class);
Method getParameter = Class.forName("org.postgresql.PGNotification").getMethod("getParameter");
while (running):
    Object[] notifications = (Object[]) getNotifications.invoke(pg, (int) pollMs);  // blocks up to pollMs
    for (n : notifications) log.info("cp_events: {}", getParameter.invoke(n));
    if (conn.isClosed()) throw new IllegalStateException("LISTEN connection closed");
```
- `getNotifications(pollMs)` blocks up to `pollMs` waiting for notifications, then returns (possibly null). The explicit `conn.isClosed()` check turns a silently-dead connection into a thrown exception so the **outer loop reconnects** instead of spinning forever on a corpse.
- The reflection (`Class.forName` / `unwrap` / `getMethod`) keeps this class compilable without importing PG-specific types, and works through the connection-pool wrapper via JDBC `unwrap`.
- **Gotcha:** it logs *only the parameter* (the notification payload, i.e. the JSON envelope from `OutboxDeliveryScheduler.notify`). It does no parsing, dedup, or downstream processing — purely diagnostic.

### Method: `sleepBackoff(int failures)` (private)
Capped exponential backoff between reconnects: `BASE * 2^(failures-1)`, capped at 60 s (with a `shift >= 16` overflow guard mirroring the scheduler's). On `InterruptedException` it re-sets the interrupt flag **and** flips `running = false` — i.e. an interrupt during backoff is treated as a shutdown signal.

### Method: `stop()` — `@PreDestroy`
Flips `running = false`, interrupts the worker thread (to break it out of a blocking poll/sleep), and `closeQuietly()` the connection. Clean shutdown.

### Method: `closeQuietly()` (private)
Null-safe, exception-swallowing close of `conn`, then nulls the field. Called from both `run()`'s `finally` and `stop()`.

### Collaborators & gotchas
- Subscribes to the same `cp_events` channel that `OutboxDeliveryScheduler` (and conceptually the broader system) NOTIFYs on.
- **Operational note:** enabling it (`app.events.listener.enabled=true`) holds one extra DB connection open per app instance for the listener's lifetime. Keep it off in production unless actively debugging.
- Single-channel, single-thread, log-only by design. If you need real event-driven processing, add a durable consumer — do not extend this diagnostic tail into a delivery mechanism.

---

## Cross-package collaborators referenced above

- **`com.example.cp.subscriptions.OutboxPublisher`** — the **enqueue** side. `publish(aggregateType, aggregateId, eventType, payload)` serializes the payload `Map` and `INSERT`s an `outbox_events` row (`payload_json` cast `::jsonb`). Called inside domain transactions; relies on DB defaults for `id`/`occurred_at`/`status`/`attempts`. Bean name `subscriptionOutboxPublisher`.
- **`com.example.cp.webhooks.WebhookDispatchScheduler`** — the **other** consumer of `outbox_events`, driven by the `fanned_out_at` cursor (not `status`). It claims un-fanned rows `FOR UPDATE SKIP LOCKED`, enqueues per-subscription `webhook_deliveries`, stamps `fanned_out_at`, and delivers signed HTTP POSTs. Its existence is the reason `purgeOldEvents()` requires `fanned_out_at IS NOT NULL`.
- **`com.example.cp.common.PageRequestParams`** — supplies `DEFAULT_SIZE` (20) and `MAX_SIZE` (200) constants used by `EventStreamController` to clamp page sizes.
- **`com.example.cp.ControlPanelApplication`** — carries `@EnableScheduling`, which activates both `@Scheduled` methods in `OutboxDeliveryScheduler`.

## Configuration keys touched by this package

| Property | Default | Used by |
|----------|---------|---------|
| `app.outbox.retention` | `P30D` | `OutboxDeliveryScheduler.purgeOldEvents` cutoff |
| `app.outbox.purge.fixed-delay` | `PT1H` | purge sweep interval |
| `app.outbox.purge.initial-delay` | `PT5M` | purge sweep first-run delay |
| `app.events.listener.enabled` | `false` (absent) | gates `EventListener` registration |
| `app.events.listener.poll-ms` | `1000` | `EventListener` `getNotifications` block time |
| (RBAC) `event.read` authority | — | required by `EventStreamController` |

## Relevant database migrations

- `db/changelog/changes/11-outbox.sql` — creates `outbox_events` (base columns) + unpublished/aggregate indexes.
- `db/changelog/changes/14-outbox-reliability.sql` — adds `attempts/next_attempt_at/last_error/status`, the `chk_outbox_status` CHECK, the `idx_outbox_events_claimable` partial index, and backfills delivered rows to `PUBLISHED`.
- `db/changelog/changes/18-webhook-fanout-integrity.sql` — adds `fanned_out_at`, the `idx_outbox_events_unfanned` partial index, and the plain `idx_outbox_events_occurred_at` b-tree used by the read feed and range scans.
