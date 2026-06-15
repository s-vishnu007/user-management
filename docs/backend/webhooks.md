# `com.example.cp.webhooks` — Signed outbound webhooks (durable fan-out + retrying delivery)

## Module overview

This package is the **outbound integration channel** of the control panel: it lets a tenant
register an HTTPS endpoint and receive a signed HTTP `POST` for each domain event their org
produces (subscription activated, license revoked, license expiring, …). It is **fed from the
transactional outbox** (`outbox_events`, owned by `com.example.cp.events`) rather than from the
business code directly, which decouples "an event happened" from "an endpoint was eventually
notified" and gives us at-least-once delivery semantics even across crashes and multiple app
instances.

The package has three jobs and a clean split between them:

1. **Registration / CRUD** (`WebhookController`, `WebhookSubscription`, `WebhookSubscriptionRepository`)
   — per-org management of endpoints, generating and one-time-returning an HMAC secret, and SSRF-vetting
   the URL at registration time.
2. **Durable fan-out + delivery** (`WebhookDispatchScheduler`, `WebhookDelivery`,
   `WebhookDeliveryRepository`) — a two-phase background pump that (a) durably claims un-fanned-out
   outbox events and explodes each into one delivery row per matching subscription, then (b) claims due
   delivery rows and POSTs them with capped exponential-backoff retries.
3. **Security primitives** (`WebhookSigner`, `WebhookUrlGuard`) — the `X-CP-Signature` HMAC scheme and a
   **per-delivery DNS-rebind SSRF re-check** that re-resolves and re-vets the destination immediately
   before every POST.

### How it fits the bigger picture

```
business code → events.OutboxEvent (outbox_events, committed in the same TX)
                         │
   events.OutboxDeliveryScheduler ──► Postgres LISTEN/NOTIFY stream (SSE, internal)   [status machine]
                         │
   webhooks.WebhookDispatchScheduler.fanOut()  ──► reads outbox_events.fanned_out_at  [separate cursor]
                         │  (one PENDING webhook_deliveries row per matching active subscription)
                         ▼
   webhooks.WebhookDispatchScheduler.deliverDueBatch() ──► WebhookUrlGuard.resolveAndPin()
                         │                                  WebhookSigner.sign()
                         ▼
                  customer HTTPS endpoint (X-CP-* headers, signed body)
```

Two crucial design facts to internalize before reading further:

- **The webhook fan-out and the NOTIFY publisher are independent consumers of the same outbox.** The
  NOTIFY publisher (`events.OutboxDeliveryScheduler`) drives the `outbox_events.status`
  (`PENDING`/`PUBLISHED`/`FAILED`) machine for the internal SSE event stream. The webhook fan-out uses a
  **completely separate column**, `outbox_events.fanned_out_at` (added in `18-webhook-fanout-integrity.sql`),
  as its own durable cursor. Because they use different columns, neither blocks or rewinds the other —
  the webhook pump "never contends with the NOTIFY publisher's own status machine."
- **The signing secret and the destination URL are the two assets this package guards.** The secret is
  encrypted at rest with `keys.KeyEncryptor` (versioned AES-GCM, KEK-rotatable) and returned in plaintext
  exactly once. The URL is an SSRF risk and is vetted twice: once at registration (`sso.UrlGuard`) and
  again, with IP-pinning intent, immediately before every single delivery (`WebhookUrlGuard`).

### Persistence model (from `15-webhooks.sql` + `18-webhook-fanout-integrity.sql`)

| Table | Purpose | Key columns / constraints |
|---|---|---|
| `webhook_subscriptions` | per-org endpoint registration | `org_id` FK→`organizations` **ON DELETE CASCADE**; `secret_enc BYTEA`; `event_types TEXT` (CSV, NULL = all); partial index on `active = TRUE` |
| `webhook_deliveries` | one row per `(subscription, event)` | **`UNIQUE (subscription_id, event_id)`** (idempotent fan-out); `subscription_id` FK→`webhook_subscriptions` **ON DELETE CASCADE**; `status CHECK IN (PENDING,DELIVERED,FAILED)`; partial index `(next_attempt_at, created_at) WHERE status='PENDING'` (the claim index) |
| `outbox_events.fanned_out_at` | the webhook fan-out cursor (added later) | partial index `(occurred_at) WHERE fanned_out_at IS NULL` keeps the claim scan index-only |

> Gotcha — **`event_id` is NOT a declared FK.** `webhook_deliveries.event_id` references
> `outbox_events.id` only by convention (the column comment says so), there is no DB-level foreign key.
> The delivery claim query nonetheless `JOIN`s `outbox_events e ON e.id = d.event_id`, so if the outbox
> row were ever purged out from under a still-`PENDING` delivery, that delivery would simply stop being
> selected by the claim (the inner join drops it) and would never be delivered or marked terminal. In
> practice outbox retention outlives delivery retention, so this does not bite, but it is the one
> referential gap in the schema.

---

## `WebhookSubscription.java`

**Responsibility.** JPA entity for a single per-org webhook registration. Maps 1:1 to the
`webhook_subscriptions` table. Pure data holder (Lombok `@Getter/@Setter/@Builder/@NoArgsConstructor/@AllArgsConstructor`).

**Fields**

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | assigned by the app (`Ids.newId()`), not the DB default |
| `UUID orgId` | `org_id` | tenant owner; all CRUD is scoped by this |
| `String url` | `url` | the destination; stored verbatim as registered |
| `byte[] secretEnc` | `secret_enc` | **AES-GCM blob** from `KeyEncryptor`. Never null, never returned in any DTO. The plaintext HMAC secret is held nowhere persistent. |
| `String eventTypes` | `event_types` | CSV of subscribed `event_type` names; **null/blank = subscribe to ALL events**. Matching done in the scheduler. |
| `boolean active` | `active` | only `active = true` subscriptions are fanned out to |
| `OffsetDateTime createdAt` | `created_at` | set by the controller at create time |

**Why it exists / gotchas.** The class-level Javadoc encodes the secret-handling contract: `secretEnc`
is *write-once-readable-by-the-scheduler-only* — it is decrypted in `WebhookDispatchScheduler.attemptDelivery`
to compute the signature and is never echoed back to a client. The `eventTypes` "null means all" convention
is load-bearing and is duplicated in two places (`WebhookDispatchScheduler.matches` and
`WebhookController.normalizeEventTypes`); keep them consistent.

**Collaborators.** Created/read by `WebhookController`; read by `WebhookDispatchScheduler.fanOut`
(via `WebhookSubscriptionRepository.findByOrgIdAndActiveTrue`). The deletion of an org cascades to its
subscriptions (DB-level `ON DELETE CASCADE`), and deletion of a subscription cascades to its delivery rows.

---

## `WebhookDelivery.java`

**Responsibility.** JPA entity for one **delivery attempt-set** for a single `(subscription, event)`
pair. Maps to `webhook_deliveries`. The unique constraint on `(subscription_id, event_id)` is what makes
the whole fan-out idempotent.

**Nested enum `WebhookDelivery.Status`**

| Value | Meaning | Re-claimable? |
|---|---|---|
| `PENDING` | awaiting first POST or a retry; claimable once `next_attempt_at` is null or in the past | yes |
| `DELIVERED` | endpoint returned 2xx; `delivered_at` stamped | no (terminal) |
| `FAILED` | exceeded `maxAttempts`; poison/quarantined | no (terminal) |

**Fields of note**

| Field | Column | Notes |
|---|---|---|
| `status` | `status` (`@Enumerated(STRING)`, len 16) | `@Builder.Default = PENDING` |
| `attempts` | `attempts` | `@Builder.Default = 0`; incremented on every terminal-or-retry write |
| `Integer responseStatus` | `response_status` | last HTTP status seen; **null** for network/SSRF/decrypt failures (no status was observed) |
| `OffsetDateTime nextAttemptAt` | `next_attempt_at` | earliest re-claim time; **null = immediately eligible** |
| `String lastError` | `last_error` | truncated (2000 chars) failure message, kept for diagnostics |
| `deliveredAt` | `delivered_at` | set only on success |

**Why it exists / important nuance.** Although this is a JPA entity, the **scheduler does NOT mutate it
through JPA** — fan-out inserts and delivery updates are all done with raw `JdbcTemplate` SQL (see the
scheduler). The entity exists mainly so `WebhookDeliveryRepository` can express the retention-sweep query
in JPQL and so the row shape is validated against the schema (`ddl-auto=validate`). When reading the
scheduler, do not expect `setStatus(...)`/`save(...)` calls — the lifecycle transitions are hand-written
`UPDATE` statements.

---

## `WebhookSubscriptionRepository.java`

**Responsibility.** Spring Data JPA repository for `WebhookSubscription`. Three derived queries, each with
a deliberate purpose.

| Method | Used by | Why |
|---|---|---|
| `List<WebhookSubscription> findByOrgIdOrderByCreatedAtDesc(UUID orgId)` | `WebhookController.list` | newest-first listing for the admin UI |
| `Optional<WebhookSubscription> findByIdAndOrgId(UUID id, UUID orgId)` | `WebhookController.delete` | **tenant-scoped lookup** — a row belonging to another org returns empty → 404, never a cross-tenant delete (IDOR defense) |
| `List<WebhookSubscription> findByOrgIdAndActiveTrue(UUID orgId)` | `WebhookDispatchScheduler.fanOut` | only active subscriptions receive fan-out |

**Gotcha.** `findByIdAndOrgId` is the single-row IDOR guard; the comment is explicit that "CRUD on a
single subscription cannot escape the path org." Any new single-subscription endpoint must use this method
(or an equivalent `(id, org_id)` scope), not `findById`.

---

## `WebhookDeliveryRepository.java`

**Responsibility.** Spring Data JPA repository for `WebhookDelivery`. Two members:

- `boolean existsBySubscriptionIdAndEventId(UUID subscriptionId, UUID eventId)` — a convenience existence
  check (the durable idempotency actually comes from the DB unique constraint + `ON CONFLICT DO NOTHING`
  in the scheduler's `enqueue`, so this is not the primary dedup path; it is available for callers/tests).
- `int deleteTerminalOlderThan(OffsetDateTime cutoff)` — a `@Modifying @Query` JPQL bulk delete:

  ```sql
  DELETE FROM WebhookDelivery d
  WHERE d.status <> PENDING AND d.createdAt < :cutoff
  ```

  This is the **retention sweep**. It deletes only terminal (`DELIVERED`/`FAILED`) rows; `PENDING` rows
  are *never* purged because they are still owed delivery or a retry. Returns the rows removed (logged by
  the scheduler when > 0).

**Collaborators.** `deleteTerminalOlderThan` is called by `WebhookDispatchScheduler.cleanup()` on its own
schedule. Because it is `@Modifying`, the caller runs it inside a transaction (the `cleanup()` tick).

---

## `WebhookSigner.java`

**Responsibility.** Computes the `X-CP-Signature` header value for an outbound delivery. Stateless
`@Component`.

**Public API**

```java
String sign(byte[] secret, String timestamp, String body)
```

- Builds the **signing input** as `"<timestamp>.<body>"` (a null timestamp or body is treated as the empty
  string) and returns `"sha256=" + lowercaseHex(HMAC_SHA256(secret, signingInput))`.
- The timestamp is bound into the MAC so a captured signature **cannot be replayed against a different
  timestamp** — the receiver recomputes the HMAC over `X-CP-Timestamp` + "." + raw-body and compares. This
  is the same scheme Stripe/GitHub-style webhooks use.
- Throws `IllegalArgumentException` if `secret == null` (a programming error — the secret must have been
  decrypted by this point).

**Internals.** `hmac(...)` uses JCA `Mac.getInstance("HmacSHA256")`; the `NoSuchAlgorithm/InvalidKey`
catch is treated as unreachable (HmacSHA256 is JCA-mandated) and rethrown as `IllegalStateException`.
`toHex(...)` is a manual lowercase hex encoder (no per-byte `String.format`, so it is allocation-light).

**Why it exists / gotchas.**
- The signature covers **exactly the bytes that are POSTed** — the JSON envelope produced by
  `WebhookDispatchScheduler.buildBody`, serialized once and used both as the signing input and as the
  request body. Receivers MUST verify over the *raw* received body, not a re-serialized parse, or
  whitespace/key-ordering differences will break verification.
- The `secret` parameter is the **decrypted plaintext** of `WebhookSubscription.secretEnc`, not the blob.
- There is no constant-time comparison here because this side only *produces* a signature; constant-time
  comparison is the receiver's responsibility.

**Collaborators.** Called once per attempt by `WebhookDispatchScheduler.attemptDelivery`.

---

## `WebhookController.java`

**Responsibility.** REST CRUD for per-org webhook subscriptions under
`/api/v1/orgs/{orgId}/webhooks`. Three endpoints: list, create, delete.

**Authorization model.** Every method is `@PreAuthorize("@tenantAccess.canManageOrg(#orgId)")` — only a
super-admin or an OWNER/ADMIN of the path org may manage webhooks, and (per the class Javadoc) API-key
principals are denied writes by that checker. Single-row delete *additionally* scopes by `(id, org_id)`
so an attacker who guesses a subscription id in another tenant gets a 404.

**Dependencies (constructor-injected):** `WebhookSubscriptionRepository repo`, `KeyEncryptor keyEncryptor`,
`UrlGuard urlGuard` (the **SSO** `com.example.cp.sso.UrlGuard`, used here for registration-time validation),
`AuditWriter auditWriter`, plus a private `SecureRandom rng`.

**Constant.** `SECRET_BYTES = 32` — the generated secret is 32 random bytes, base64url-encoded
(no padding).

### Endpoints

**`GET /` → `List<WebhookDto>`** (`@Transactional(readOnly = true)`)
Lists the org's subscriptions newest-first, mapping each entity to a `WebhookDto` (which deliberately omits
the secret).

**`POST /` → `201 CreateResponse`** (`@Transactional`)
The create flow, in order — **the ordering is security-relevant**:

1. **SSRF-validate the URL first** via `urlGuard.validate(body.url())`. On `UrlGuard.SsrfException`, log the
   internal detail at WARN and rethrow as `ApiException.badRequest(e.publicMessage())` — the caller gets a
   generic message, the precise reason stays server-side. *Nothing is persisted until the URL passes.*
2. **Generate the secret server-side:** 32 `SecureRandom` bytes → base64url(no padding) plaintext → encrypt
   with `keyEncryptor.encrypt(plaintext.getBytes(UTF_8))` to get `secretEnc`. The integrator never chooses
   the secret.
3. **Normalize `eventTypes`** (trim/drop-blanks/rejoin; empty → null = all events).
4. **Persist** a new `WebhookSubscription` (`active = true`, `createdAt = now()`, id from `Ids.newId()`).
5. **Fail-closed audit:** write a `webhook.subscription.created` audit row *in the same transaction*
   (`auditWriter.record(..., recorded=true)`) then `AuditContext.markRecorded()` to suppress the
   interceptor's duplicate. The audit payload includes `org_id`, `url`, and `event_types` (`"*"` when null).
   The comment notes this commits atomically with the subscription because it "opens an outbound data
   channel."
6. **Return** `201` with `CreateResponse(WebhookDto, plaintextSecret)` — **the only time the plaintext
   secret is ever returned.**

**`DELETE /{id}` → `204`** (`@Transactional`)
Looks up via `findByIdAndOrgId(id, orgId)` (→ 404 if absent/other-tenant), deletes, then writes a
fail-closed `webhook.subscription.deleted` audit row (payload `org_id`, `url`) and `markRecorded()`.

### Nested types

| Type | Shape | Notes |
|---|---|---|
| `record CreateRequest(@NotBlank String url, String eventTypes)` | request body | `url` required; `eventTypes` optional CSV |
| `record CreateResponse(WebhookDto webhook, String secret)` | create response | **secret here only** |
| `record WebhookDto(id, orgId, url, eventTypes, active, createdAt)` | safe projection | `WebhookDto.from(entity)` — no secret field exists, so it *cannot* leak |

### Private helpers

- `normalizeEventTypes(String csv)` — splits on `,`, trims, drops empties, rejoins; returns `null` if the
  result is empty/blank. Mirrors the "null = all" convention.
- `actorUserId()` — resolves the acting user id, preferring `AuditContext.currentActorUserId()` and falling
  back to `SecurityUtils.currentUser().userId()`.

**Gotchas for a new engineer.**
- Registration uses **`sso.UrlGuard`** (validate-only); delivery uses **`webhooks.WebhookUrlGuard`**
  (validate + pin). They are intentionally two classes with the same policy — see `WebhookUrlGuard` below.
- The secret is unrecoverable after creation. There is no rotate/reveal endpoint here; losing it means
  deleting and re-creating the subscription. (KEK rotation re-encrypts the stored blob but does not change
  the plaintext secret.)
- `WebhookDto` is the deliberate seam preventing secret leakage — never add the secret to it.

---

## `WebhookUrlGuard.java`

**Responsibility.** The **SSRF chokepoint for the delivery path**. A `@Component` that re-parses,
re-resolves, and re-vets a destination URL **immediately before each POST**, returning a `PinnedTarget`
to send to. It mirrors `sso.UrlGuard`'s policy but lives in the webhooks package so the delivery scheduler
can call it per attempt.

**Why a second guard at all (DNS-rebind defense).** A webhook URL is vetted once at registration. But the
JDK `HttpClient` re-resolves the hostname at *send* time, so a malicious org admin can register a benign
public host and **later repoint its DNS at an internal address** (e.g. `169.254.169.254` cloud metadata,
or an internal HTTPS service). `resolveAndPin` closes this by re-running the full resolve + IP-policy check
right before the request. The class Javadoc is candid that a **narrow residual TOCTOU window** remains
between this check and the `HttpClient`'s own resolution (true per-connection TLS pinning is non-trivial);
that window is accepted because deliveries are gated to org admins and are *blind* (redirects disabled,
response body discarded — nothing exfiltrates back).

**Config (constructor `@Value`s — shared with SSO):**

| Property | Default | Effect |
|---|---|---|
| `app.sso.url-guard.allow-http` | `false` | when true, also allows `http://` and adds port 80 |
| `app.sso.url-guard.allowed-ports` | (empty) | extra allowed ports (CSV); 443 always allowed, 80 added iff `allow-http` |

Non-numeric port tokens are silently skipped (fail-closed: an unparseable port is simply not allowed).

### `PinnedTarget resolveAndPin(String rawUrl) throws SsrfException`

Validation pipeline, in order (any failure throws `SsrfException`, never reaches an internal address):

1. reject null/blank URL;
2. `new URI(rawUrl)` then `uri.toURL()` to force a strict parse (catches `URISyntaxException` /
   `MalformedURLException` / `IllegalArgumentException`);
3. scheme must be `https` (or `http` iff `allowHttp`);
4. **reject any `userinfo`** in the URL (`user:pass@host` is an SSRF/parsing-confusion vector);
5. host must be present;
6. effective port (explicit, or 443/80 by scheme) must be in the allow-list;
7. `InetAddress.getAllByName(host)` — reject if it resolves to nothing;
8. **every** resolved address must pass `isDisallowed` → false (a multi-record host where *any* address is
   internal is rejected — a deliberate guard against split-horizon/round-robin rebind tricks);
9. return `PinnedTarget(uri, hostHeader)` where `hostHeader` is `host` (with `:port` only if the URL had an
   explicit port).

> Note on "pinning": the returned `PinnedTarget` carries the original `URI` and a `hostHeader`. The class
> name and Javadoc speak of pinning to the vetted IP, but in the current implementation the scheduler sends
> to `target.requestUri()` (the hostname-based URI) and the JDK client re-resolves — so the guarantee is
> "re-validated at send time," and the `hostHeader` field is the seam for true IP-pinning. This is the
> accepted residual TOCTOU the Javadoc documents. Do not assume the kernel connection is literally bound to
> the address vetted in step 8.

### IP policy (private)

`isDisallowed(InetAddress raw)` — **allow-list-of-deny, fail-closed**: anything not positively classifiable
as routable-public is rejected. First `unwrapMapped` (IPv4-mapped/compatible IPv6 → embedded IPv4), then
reject if `isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress || isSiteLocalAddress ||
isMulticastAddress`, then `isAddrPrivate`.

`isAddrPrivate(InetAddress)` covers the ranges the JDK flags miss:

| Family | Rejected ranges |
|---|---|
| IPv4 | `0.0.0.0/8`, `10/8`, `100.64.0.0/10` (CGNAT), `127/8`, **`169.254/16`** (incl. `169.254.169.254` cloud metadata), `172.16/12`, `192.168/16` |
| IPv6 | `fc00::/7` (ULA), `::`/`::1` |
| other | unknown address family → `true` (fail closed) |

`unwrapMapped(InetAddress)` detects `::ffff:a.b.c.d` (mapped) and `::a.b.c.d` (compatible) and returns the
embedded IPv4 so the IPv4 policy applies — while carefully *not* mis-unwrapping `::`/`::1` (the compat
zero-address check), which are left to the IPv6 zero-check.

### Nested types

- `record PinnedTarget(URI requestUri, String hostHeader)` — the vetted send target.
- `static class SsrfException extends RuntimeException` — message is always the generic
  `"webhook destination not allowed"`; the real reason is in `internalDetail()` (logged/recorded, never
  returned to a client). `package-private Set<Integer> allowedPorts()` is a test seam.

**Gotchas.**
- This class duplicates `sso.UrlGuard`'s IP logic on purpose; if you tighten one (e.g. add a new reserved
  range), update **both**.
- `SsrfException` here carries only `internalDetail` (no separate public message) — the scheduler maps it
  into a delivery `last_error`, while `sso.UrlGuard.SsrfException` carries both a public and internal
  message because it surfaces to an API caller.

---

## `WebhookDispatchScheduler.java`

**Responsibility.** The heart of the package: a `@Component` background pump (driven by Spring
`@Scheduled`, enabled by `@EnableScheduling` on `ControlPanelApplication`) that fans the transactional
outbox out to subscriptions and delivers each as a signed POST with retries. It does **all** its DB work
via raw `JdbcTemplate` SQL (claims, inserts, updates) so it can use Postgres `FOR UPDATE SKIP LOCKED`
directly.

### Dependencies & configuration

Constructor-injected: `WebhookSubscriptionRepository subRepo`, `WebhookDeliveryRepository deliveryRepo`,
`SubscriptionRepository subscriptionRepo`, `LicenseTokenRepository tokenRepo`, `WebhookSigner signer`,
`KeyEncryptor keyEncryptor`, `JdbcTemplate jdbc`, `WebhookUrlGuard urlGuard`, **`@Lazy WebhookDispatchScheduler self`**,
plus two `@Value`s.

| Knob | Property | Default | Meaning |
|---|---|---|---|
| dispatch cadence | `app.webhooks.dispatch-interval-ms` | `5000` | `fixedDelay` between `dispatch()` ticks |
| cleanup cadence | `app.webhooks.cleanup-interval-ms` | `3600000` (1h) | `fixedDelay` between retention sweeps |
| `maxAttempts` | `app.webhooks.max-attempts` | `8` | attempts before a delivery is parked `FAILED`; non-positive → `DEFAULT_MAX_ATTEMPTS=8` |
| `requestTimeout` | `app.webhooks.timeout` | `PT10S` | per-attempt connect+read timeout; null/zero/negative → `DEFAULT_REQUEST_TIMEOUT=10s` |

Compile-time constants: `DELIVER_BATCH=100`, `FANOUT_BATCH=500`, `BACKOFF_BASE=10s`, `BACKOFF_CAP=1h`,
`MAX_ERROR_LEN=2000`, `CONNECT_TIMEOUT=3s`, `DELIVERY_RETENTION=30d`.

The `HttpClient` is built with **`followRedirects(NEVER)`** (a 3xx to an internal address must not be
chased — SSRF) and `connectTimeout(3s)`. It is held in a mutable field so `setHttpClientForTest` can swap a
stub (the only non-final piece of state besides the in-flight flag).

### The two concurrency-critical fields

**`@Lazy WebhookDispatchScheduler self`** — a self-reference so the `@Scheduled` `dispatch()` method invokes
the `@Transactional` phase methods **through the Spring proxy**. This is the single most important detail in
the file:

> A direct `this.fanOut()` call would bypass the proxy, so `@Transactional` would not apply, and the
> `FOR UPDATE SKIP LOCKED` claim would run in **autocommit** — releasing the row locks immediately and
> allowing **duplicate deliveries across instances**. Always go through `self.` (the comment tags this
> "P2 self-invocation"). `@Lazy` breaks the constructor self-injection cycle.

**`ExecutorService deliveryExecutor`** — a dedicated **single-thread** executor (daemon thread named
`webhook-delivery`) for the *delivery* phase only. Fan-out runs synchronously on the shared `@Scheduled`
thread; delivery — the slow, tarpit-prone phase that may synchronously POST up to 100 endpoints — runs here.

> **Why the split (P1-11 scheduler starvation):** a tarpit endpoint that stalls a delivery batch must never
> stretch the gap between fan-out ticks. If fan-out and delivery shared a thread, one tenant's hanging
> endpoint could delay fan-out enough that *other* tenants' events age out / are dropped. Separating them
> guarantees fan-out keeps ticking regardless of how slow any endpoint is.

**`AtomicBoolean deliveryInFlight`** — at most one delivery batch runs at a time. Each tick does
`compareAndSet(false, true)` before submitting; if a previous batch is still running (hung endpoint), the
tick **skips** submitting rather than queueing unboundedly. The rows simply stay claimable for the in-flight
batch or a later tick. `@PreDestroy shutdown()` calls `deliveryExecutor.shutdownNow()`.

### Entry points (`@Scheduled`)

**`dispatch()`** (every `dispatch-interval-ms`, default 5s):
1. `self.fanOut()` synchronously (bounded + fast: a claim + a few inserts/updates), wrapped in try/catch so
   a fan-out error is logged but does not kill the tick.
2. If `deliveryInFlight.compareAndSet(false, true)`, submit `self.deliverDueBatch()` to `deliveryExecutor`,
   clearing the flag in a `finally`. Otherwise skip (a batch is already running).

**`cleanup()`** (every `cleanup-interval-ms`, default 1h): computes `cutoff = now − 30d`, calls
`deliveryRepo.deleteTerminalOlderThan(cutoff)`, logs the count if > 0. Wrapped in try/catch.

### Phase 1 — `@Transactional void fanOut()` (durable fan-out cursor)

```sql
SELECT id, aggregate_type, aggregate_id, event_type, payload_json
FROM outbox_events
WHERE fanned_out_at IS NULL
ORDER BY occurred_at ASC
LIMIT 500
FOR UPDATE SKIP LOCKED
```

Flow per claimed event:
1. `resolveOrg(event)` → the owning `org_id` (see below).
2. If resolved, `subRepo.findByOrgIdAndActiveTrue(orgId)`, and for each subscription where
   `matches(sub.eventTypes, event.eventType)`, call `enqueue(subId, eventId)`.
3. **Always** `markFannedOut(eventId, now)` — *even when no subscription matched or the org was
   unresolved* — so a permanently-unroutable event is not re-scanned forever. The marker means "we have
   considered this event," not "it produced a delivery."

**Why this is a durable cursor, not a lookback window:** `fanned_out_at` is a *per-event* durable marker.
An event stays claimable until a fan-out tick commits its `fanned_out_at` stamp, so an event can **never be
silently dropped by ageing out of a time window**. If a tick crashes mid-way, the un-stamped rows are simply
unclaimed for the next tick (at-least-once). `FOR UPDATE SKIP LOCKED` lets multiple instances claim disjoint
batches without contention or double-processing.

`enqueue(subId, eventId)` — idempotent insert:

```sql
INSERT INTO webhook_deliveries (id, subscription_id, event_id, status, attempts, created_at)
VALUES (?, ?, ?, 'PENDING', 0, now())
ON CONFLICT (subscription_id, event_id) DO NOTHING
```

The `(subscription_id, event_id)` unique constraint + `ON CONFLICT DO NOTHING` guarantees **at most one
delivery row per pair**, even if the same event is somehow processed twice across ticks or instances.

`markFannedOut(eventId, now)` — `UPDATE outbox_events SET fanned_out_at = ? WHERE id = ?`.

**`resolveOrg(ClaimedEvent)`** — figuring out which org owns an event (so we fan out to the right
subscriptions):

| `aggregate_type` | Resolution path |
|---|---|
| `"subscription"` | `aggregate_id` is the subscription id → `subscriptionRepo.findById(subId).map(getOrgId)` |
| `"license_token"` | `aggregate_id` is the JTI → `tokenRepo.findByJti(jti)` → `subscriptionId` → `subscriptionRepo.findById(...).map(getOrgId)` |
| any (fallback) | `orgFromPayload(payloadJson)` — read a textual `org_id` field from the event JSON |

The aggregate lookup is the **primary** path because not every event carries `org_id` in its payload (e.g.
`SubscriptionSuspended`/`Cancelled`). The payload fallback covers cases where the aggregate row has been
deleted (e.g. a deleted subscription). Helpers `orgFromPayload` (best-effort Jackson parse; any failure →
null) and `parseUuid` (null on bad UUID) keep this defensive — an unresolved org just skips fan-out and the
event is still marked fanned-out.

**`static boolean matches(String eventTypesCsv, String eventType)`** — CSV filter: null/blank filter
matches everything; otherwise an exact, trimmed, **case-sensitive** token match. A null `eventType` against
a non-blank filter returns false.

### Phase 2 — `@Transactional void deliverDueBatch()` (claim + deliver)

```sql
SELECT d.id, d.subscription_id, d.event_id, d.attempts,
       s.url, s.secret_enc, e.event_type, e.aggregate_type, e.aggregate_id, e.payload_json
FROM webhook_deliveries d
JOIN webhook_subscriptions s ON s.id = d.subscription_id
JOIN outbox_events e ON e.id = d.event_id
WHERE d.status = 'PENDING'
  AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= now())
ORDER BY d.created_at ASC
LIMIT 100
FOR UPDATE OF d SKIP LOCKED
```

Note `FOR UPDATE OF d` — it locks **only the delivery rows** (not the joined subscription/outbox rows), so
sibling instances never deliver the same delivery row but are not blocked from reading the same subscription.
The join pulls everything needed for one POST in a single query: the URL + encrypted secret + the event
fields to build the body. The claim is backed by the partial index `(next_attempt_at, created_at) WHERE
status='PENDING'`. Each row is processed via `attemptDelivery`; a catch-all wraps each row so an unexpected
exception isolates that row as a failure (`markFailure(row, now, -1, ...)`) rather than aborting the batch.

**`void attemptDelivery(ClaimedDelivery row, OffsetDateTime now)`** — one attempt:

1. `buildBody(row)` → the JSON envelope (the exact bytes signed and sent).
2. `timestamp = epochSeconds(now)`.
3. **Decrypt the secret:** `keyEncryptor.decrypt(row.secretEnc())`. On failure → `markFailure(..., null,
   "secret decrypt failed: ...")` and return. (An undecryptable secret never recovers, so it is counted as
   an attempt and rides backoff into the `FAILED` poison state — e.g. if a KEK was dropped.)
4. `signature = signer.sign(secret, timestamp, body)`.
5. **DNS-rebind SSRF re-check:** `urlGuard.resolveAndPin(row.url())`. On `SsrfException` → `markFailure(...,
   null, "ssrf re-check failed: " + e.internalDetail())` and return. This is where the per-delivery guard
   runs.
6. Build the `HttpRequest` to `target.requestUri()` with `timeout(requestTimeout)` and headers:

   | Header | Value |
   |---|---|
   | `Content-Type` | `application/json` |
   | `X-CP-Event` | the outbox `event_type` (empty string if null) |
   | `X-CP-Delivery` | the `webhook_deliveries.id` — **stable across retries**, usable by the receiver as an idempotency key |
   | `X-CP-Timestamp` | epoch seconds the request was signed at |
   | `X-CP-Signature` | `"sha256=" + HMAC_SHA256(secret, "<timestamp>.<body>")` |

   (`IllegalArgumentException` from the builder → `markFailure(..., "invalid url: ...")`.)
7. `httpClient.send(request, BodyHandlers.discarding())` — **the response body is discarded** (blind
   delivery; nothing comes back to the server, reinforcing the SSRF posture).
   - 2xx → `markDelivered(row, now, status)`.
   - any other status → `markFailure(row, now, status, "non-2xx response: " + status)`.
   - `IOException` → `markFailure(..., null, "io error: ...")`.
   - `InterruptedException` → restore the interrupt flag, then `markFailure(..., null, "interrupted")`.

**`String buildBody(ClaimedDelivery row)`** — constructs the delivery envelope with Jackson:

```json
{
  "eventId": "...", "deliveryId": "...", "eventType": "...",
  "aggregateType": "...", "aggregateId": "...",
  "payload": { ...the outbox payload, parsed as a nested object... }
}
```

`payload` is the outbox `payload_json` re-parsed into a nested JSON node (falling back to `{}` if blank, or
to the raw string if it does not parse). This serialized string is **both** what gets HMAC-signed and what
gets POSTed — they cannot diverge.

**State-transition writes (raw SQL):**

- `markDelivered(row, now, status)`:
  ```sql
  UPDATE webhook_deliveries
  SET status='DELIVERED', attempts=?, response_status=?, delivered_at=?, next_attempt_at=NULL, last_error=NULL
  WHERE id=?
  ```
  (`attempts = row.attempts() + 1`.)
- `markFailure(row, now, status, error)` — increments attempts, truncates the error to 2000 chars, then:
  - if `attempts >= maxAttempts` → set `status='FAILED'`, `next_attempt_at=NULL`, log at **ERROR**
    ("quarantined as FAILED");
  - else → keep `PENDING`, set `next_attempt_at = now + backoff(attempts)`, log at **WARN** with the
    attempt counter.

**`static Duration backoff(int attempts)`** — capped exponential backoff: `BACKOFF_BASE * 2^(attempts-1)`,
never exceeding `BACKOFF_CAP` (1h). Overflow-safe: `shift >= 62`, or a non-positive/over-cap millis result,
all collapse to `BACKOFF_CAP`. So the retry schedule is roughly 10s, 20s, 40s, 80s, …, then pinned at 1h.

### Nested records

- `record ClaimedEvent(UUID id, String aggregateType, String aggregateId, String eventType, String payloadJson)`
  — a fan-out-claimed outbox row.
- `record ClaimedDelivery(UUID id, UUID subscriptionId, UUID eventId, int attempts, String url, byte[] secretEnc, String eventType, String aggregateType, String aggregateId, String payloadJson)`
  — a delivery row pre-joined with its subscription + outbox event, ready to POST.

### Gotchas / things a new engineer must know

- **Never call the phase methods without `self.`** — direct self-invocation silently disables
  `@Transactional`, breaking the `SKIP LOCKED` claim semantics. This is the canonical Spring proxy trap and
  the reason `@Lazy self` exists.
- **All lifecycle mutations are raw SQL, not JPA.** Don't look for `delivery.setStatus(...)`/`save(...)`.
- **Delivery is single-threaded and self-throttling.** Throughput is bounded by `DELIVER_BATCH` (100) per
  cycle and one in-flight batch at a time. A genuinely high webhook volume tenant is rate-limited by this
  design; scaling means more app instances (the `SKIP LOCKED` claims partition cleanly) rather than more
  threads.
- **Decryption failures and SSRF re-check failures both consume attempts** and eventually poison the row to
  `FAILED` — they are not retried forever. A dropped KEK or a DNS rebind both end the same way.
- **Fan-out marks every considered event**, including ones with no matching subscription. Re-delivering an
  old event to a newly-created subscription is therefore *not* supported by the cursor — once an event is
  fanned out, a later subscription will only receive *future* events. (Replay would require a separate
  mechanism.)
- The `dispatch-interval-ms` / `cleanup-interval-ms` properties are not in `application.yml` (only
  `max-attempts` and `timeout` are); they fall back to the in-code `@Scheduled` defaults (5s / 1h).
- Tests drive this via two seams: `setHttpClientForTest(HttpClient)` (stub the network) and the
  package-private `maxAttempts()` / `requestTimeout()` getters and `static backoff(...)` / `static matches(...)`.

---

## Cross-cutting cheat-sheet

**End-to-end flow for one event:**

```
1. business code commits an outbox_events row (org-derivable via aggregate or payload.org_id)
2. fanOut() claims it (fanned_out_at IS NULL, SKIP LOCKED), inserts a PENDING webhook_deliveries
   row per matching active subscription (ON CONFLICT DO NOTHING), stamps fanned_out_at
3. deliverDueBatch() claims the PENDING row (status=PENDING AND next_attempt_at due, SKIP LOCKED)
4. decrypt secret (KeyEncryptor) → sign (WebhookSigner) → re-vet URL (WebhookUrlGuard.resolveAndPin)
5. POST with X-CP-* headers; 2xx → DELIVERED, else → retry w/ capped backoff or → FAILED after maxAttempts
6. cleanup() purges terminal rows older than 30d
```

**Outbound headers the receiver verifies:** recompute `HMAC_SHA256(secret, X-CP-Timestamp + "." + rawBody)`,
hex-encode, prefix `sha256=`, compare to `X-CP-Signature` in constant time; dedupe on `X-CP-Delivery`.

**Security invariants:** secret server-generated + encrypted at rest + returned once; URL vetted at
registration **and** per-delivery (DNS-rebind); redirects disabled; response discarded; all CRUD
tenant-scoped and audit-logged fail-closed.
