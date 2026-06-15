# `com.example.cp.common` — Cross-Cutting Infrastructure

> Module: `control-panel-api` · Package: `com.example.cp.common`
> Source glob: `control-panel-api/src/main/java/com/example/cp/common/**/*.java` (20 files, all documented below)

## Package overview

`com.example.cp.common` is the control-panel API's **cross-cutting toolbox** — the layer of plumbing that sits *around* every business request rather than inside any one feature. It owns the request lifecycle scaffolding (correlation IDs, access logging, request-size guards, IP-based rate limiting, idempotency), the uniform error contract (RFC-7807 `ProblemDetail` mapping for every exception the app can throw), shared value types and helpers used by virtually every controller/service (the `ApiException` hierarchy, `PagedResponse`/`PageRequestParams`, `Ids`, `AuthenticatedUser`, `SecurityUtils`), the ambient request-scoped audit/trust context (`AuditContext`, `TrustedProxyResolver`, `AuditProperties`), and the observability wiring (`ObservabilityConfig`/`MetricsService`). Most of these are framework-level components (servlet filters, an MVC interceptor, a `@RestControllerAdvice`, `@ConfigurationProperties`, a scheduled job) that are auto-wired by Spring Boot and never referenced explicitly by feature code — they "just happen" to every request.

A useful mental model is the **request pipeline**, outermost first:

```
client
  │  X-Request-Id, Idempotency-Key, Content-Length, body
  ▼
[CorrelationIdFilter]            order = HIGHEST_PRECEDENCE        (binds requestId → MDC)
[RequestSizeLimitFilter]         order = HIGHEST_PRECEDENCE + 5    (413 before body is read)
[AccessLogFilter]                order = HIGHEST_PRECEDENCE + 10   (one structured line/request)
[IdempotencyBodyCachingFilter]   order = HIGHEST_PRECEDENCE + 10   (buffers req/resp body if idempotent)
  ▼
Spring Security FilterChainProxy
  └─ [RateLimitFilter]           (only on the 4 sensitive auth POSTs, before JwtAuthFilter)
  ▼
DispatcherServlet
  └─ [IdempotencyInterceptor]    preHandle / afterCompletion on /api/**
  ▼
@RestController  ── throws ──►  [GlobalExceptionHandler]  → ProblemDetail
```

Two ThreadLocal/MDC-style ambient stores ride along: SLF4J's `MDC` (the `requestId`, set by `CorrelationIdFilter`) and `AuditContext` (a `ThreadLocal` carrying actor/action/target/outcome that the audit aspect and the exception handler both read). Both are **cleared in `finally`** to avoid leaking onto a pooled worker thread.

### How it fits the bigger picture

Everything customer-facing in the control panel — provisioning subscriptions, minting Ed25519-signed `.lic` licenses, managing users/orgs/API-keys, webhooks, billing — runs *through* this package. The feature packages (`auth`, `licensing`, `org`, `billing`, `webhook`, `audit`, …) depend on `common`, not the reverse, with one deliberate exception: this package imports `com.example.cp.audit` (`AuditWriter`, `AuditOutcome`) because the error handler and the audit context must record DENIED outcomes. The package embodies several findings from the security audit + remediation: non-spoofable client-IP resolution (`TrustedProxyResolver`), fail-closed audit on authz refusals (`GlobalExceptionHandler.recordDenied`), a JSON body-size DoS guard (`RequestSizeLimitFilter`), per-IP auth rate limiting (`RateLimitFilter`), request tracing (`CorrelationIdFilter` + `AccessLogFilter`), critical-flow metrics (`ObservabilityConfig`), and at-least-once-safe retries (`IdempotencyInterceptor`).

---

## Quick file index

| File | Kind | One-liner |
|------|------|-----------|
| `ApiException.java` | exception | Caller-safe `RuntimeException` carrying HTTP status + RFC-7807 fields; the canonical way to signal an error. |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` | Maps every exception → correct status + `ProblemDetail`; audits authn/authz refusals. |
| `PageRequestParams.java` | util | Builds a clamped, sort-parsed `Pageable` from raw query params. |
| `PagedResponse.java` | record | Stable JSON envelope `{items,total,page,size}` for list endpoints. |
| `Ids.java` | util | Mints time-ordered (UUIDv7) UUIDs for primary keys. |
| `AuthenticatedUser.java` | record | The Spring Security principal (user vs. api-key) with authority helpers. |
| `SecurityUtils.java` | util | Static accessors for the current principal / user-id / org-id. |
| `AuditContext.java` | ThreadLocal | Request-scoped audit metadata (actor/action/target/outcome) + dedup sentinel. |
| `AuditProperties.java` | `@ConfigurationProperties` | Binds `app.audit.*` (trusted proxies, fail-closed actions). |
| `TrustedProxyResolver.java` | component | Non-spoofable client-IP resolution from `X-Forwarded-For`. |
| `CorrelationIdFilter.java` | filter | Binds/echoes `X-Request-Id` and pushes `requestId` into MDC. |
| `AccessLogFilter.java` | filter | One structured access-log line per completed request. |
| `RequestSizeLimitFilter.java` | filter | Rejects oversized bodies with `413` before they are read. |
| `RateLimitFilter.java` | filter | Per-IP token-bucket `429` limiter on sensitive auth endpoints. |
| `ObservabilityConfig.java` | `@Configuration` | Micrometer common tag, `@Timed` aspect, and the `MetricsService` facade. |
| `IdempotencyInterceptor.java` | MVC interceptor | `Idempotency-Key` claim/replay/conflict logic + transactional `Store`. |
| `IdempotencyConfig.java` | `@Configuration` | Registers the interceptor + body-caching filter + TTL props. |
| `IdempotencyKey.java` | `@Entity` | Persisted idempotency record (in-flight → completed). |
| `IdempotencyKeyRepository.java` | repository | Lookup by natural key + bulk delete-expired. |
| `IdempotencyRetentionJob.java` | scheduled job | Hourly purge of expired idempotency rows. |

---

# Errors & the API contract

## `ApiException.java`

**Responsibility.** The application's single, **intentionally caller-safe** exception type. Any service or controller that wants to abort a request with a specific HTTP status throws an `ApiException`; its `detail` is the only free-text message the package allows to flow back to the client unchanged. (Contrast: `IllegalArgumentException`'s message is *never* echoed — see the exception handler — precisely because it may contain arbitrary internal strings.)

**Class:** `public class ApiException extends RuntimeException`

Immutable fields, each mirroring an RFC-7807 `ProblemDetail` slot:

| Field | Meaning |
|-------|---------|
| `HttpStatus status` | The HTTP status to return. |
| `String type` | RFC-7807 problem type URI; defaults to `"about:blank"`. |
| `String title` | Short human title. |
| `String detail` | Caller-safe explanation (also the `RuntimeException` message). |

**Constructors & factories.**

- `ApiException(status, type, title, detail)` — full form.
- `ApiException(status, title, detail)` — convenience; `type = "about:blank"`.
- Static factories give the common cases readable call sites:
  `notFound(detail)` (404), `badRequest(detail)` (400), `conflict(detail)` (409), `forbidden(detail)` (403), `unauthorized(detail)` (401).

**Collaborators.** Caught by `GlobalExceptionHandler.handleApi`, which copies the fields onto a `ProblemDetail`. Thrown by `SecurityUtils.currentUserId()`/`requireUser()` (401) and across every feature package.

**Gotcha for new engineers.** Because `RuntimeException`, it is unchecked and rolls back the surrounding `@Transactional`. When the status is 401/403 the handler records a DENIED audit row *after* that rollback (see below) — so do not assume your own audit write inside the transaction survived. Use this type for *expected* business failures; raw `IllegalArgumentException`/`NullPointerException` become an opaque 400/500 with no detail leaked.

---

## `GlobalExceptionHandler.java`

**Responsibility.** The **central error mapper**: a `@RestControllerAdvice` extending Spring's `ResponseEntityExceptionHandler` so the framework's own MVC exceptions get sensible statuses, plus app-specific handlers for the exceptions this codebase throws. Every error response is rendered as an RFC-7807 `ProblemDetail` (Spring serializes it as `application/problem+json`). Secondary duty: **audit authn/authz refusals** that would otherwise escape the normal audit aspect.

**Why extend `ResponseEntityExceptionHandler`?** Its base implementation already maps a battery of standard Spring MVC exceptions to correct statuses (rather than letting them fall through to a 500), e.g.:

| Spring exception | Status | Trigger |
|---|---|---|
| `HttpMessageNotReadableException` | 400 | malformed/empty JSON body |
| `HttpRequestMethodNotSupportedException` | 405 | wrong HTTP method |
| `HttpMediaTypeNotSupportedException` | 415 | wrong `Content-Type` |
| `HttpMediaTypeNotAcceptableException` | 406 | unsatisfiable `Accept` |
| `MissingServletRequestParameterException` / `MissingPathVariableException` | 400 | missing param/path var |
| `MethodArgumentTypeMismatchException` / `TypeMismatchException` | 400 | e.g. non-UUID bound to a `UUID` path var |
| `NoHandlerFoundException` / `NoResourceFoundException` | 404 | unknown subpath |

**Constructor / collaborators.** Injects `AuditWriter` (to persist DENIED rows) and `TrustedProxyResolver` (to resolve the client IP for those rows).

### Handler methods (status mapping)

| Handler | Catches | → Status | Notes / why |
|---|---|---|---|
| `handleApi(ApiException, WebRequest)` | `ApiException` | `ex.getStatus()` (or 500 if null) | Passes `detail`/`title`/`type` through unchanged (already safe). **If 401/403, calls `recordDenied("request.denied", …)`** so an RBAC/tenant 403 thrown in a controller is auditable after its transaction rolls back. |
| `handleAccessDenied(AccessDeniedException)` | Spring Security authz failure | 403 | Logs server-side; body is a generic `"Access is denied"` — never leaks *which* resource/why. Records DENIED `"access.denied"`. |
| `handleAuth(AuthenticationException)` | Spring Security authn failure | 401 | Generic `"Authentication required"`; records DENIED `"auth.denied"`. |
| `handleOptimisticLock(OptimisticLockingFailureException)` | `@Version` conflict / vanished versioned row | **409** | Adds `retryable=true` property + a "re-read and retry" detail. Deliberately **not** a 500 — it's a transient race. |
| `handleDataIntegrity(DataIntegrityViolationException)` | DB constraint violation (usually a lost check-then-insert UNIQUE race) | **409** | Logs `getMostSpecificCause()` but never echoes it (schema leak). |
| `handleIllegal(IllegalArgumentException)` | any `IllegalArgumentException` | 400 | **Message is logged, not echoed** — generic `"Bad request"`. Caller-facing text must go through `ApiException`. |
| `handleMethodArgumentNotValid(...)` (override) | `@Valid @RequestBody` bean-validation failures | 400 | Attaches a per-field `errors` map (`field → message`). Field names + bean-validation messages are author-controlled, so safe to surface. |
| `handleGeneric(Exception)` | anything else | 500 | Last-resort; logs full stack, returns opaque `"An unexpected error occurred"`. |

**Precedence.** The specific `@ExceptionHandler` methods win over the base class for their declared types; `handleGeneric` is the genuine catch-all. So an `ApiException(409)` becomes a 409, not a 500.

### `recordDenied(defaultAction, ex)` — fallback DENIED audit (private)

This is the subtle, security-load-bearing part. Authn/authz refusals can be thrown *outside* the controller-mutating pointcut that the normal `AuditInterceptor` aspect advises (e.g. on GET endpoints, or inside filters), so without this fallback those denials would go unaudited.

Flow:

1. **Dedup:** if `AuditContext.isRecorded()` is already true (the aspect wrote a row for this request), return — no duplicate.
2. Resolve `action` from `AuditContext.currentAction()`, else the supplied default.
3. Build the payload from `AuditContext.currentPayload()` and add `error.class` (the exception's simple name only — not its message).
4. Resolve the client IP: prefer `AuditContext.currentIp()`, else `proxyResolver.resolveClientIp(currentRequest())`.
5. Resolve the actor: prefer `AuditContext.currentActorUserId()`, else fall back to `SecurityUtils.currentUser().userId()` (the aspect may have *already cleared* the context by the time the handler runs).
6. `auditWriter.record(actorId, actorOrgId, action, targetType, targetId, payload, ip, AuditOutcome.DENIED, false)` then `AuditContext.markRecorded()`.
7. **`finally { AuditContext.clear(); }`** — this handler is the terminal step of a failed request, so it clears the ThreadLocal. **Critical concurrency note:** if the `recorded`/context state leaked onto the pooled worker thread, the *next* request on that thread would see `isRecorded()==true` and silently suppress its own audit row. Clearing here prevents that.

Failures inside `recordDenied` are swallowed with a warn — audit bookkeeping must never turn a 403 into a 500.

`currentRequest()` pulls the active `HttpServletRequest` from `RequestContextHolder`, returning `null` defensively (e.g. async dispatch where the binding is gone).

**Gotcha.** The `REQUIRES_NEW` semantics live in `AuditWriter`, not here — the point is that recording happens *after* the business transaction has unwound, so the DENIED row commits durably even though the request itself failed.

---

# Shared value types & helpers

## `PageRequestParams.java`

**Responsibility.** Turn loose, client-supplied paging query params into a safe Spring Data `Pageable`. A `final` class with a private ctor (pure static utility).

| Member | Detail |
|---|---|
| `DEFAULT_SIZE = 20`, `MAX_SIZE = 200` | Defaults and the hard ceiling. |
| `Pageable of(Integer page, Integer size, String sort)` | Clamps `page` to `≥0` (null/negative → 0), clamps `size` to `(0, MAX_SIZE]` (null/≤0 → `DEFAULT_SIZE`, else `min(size, 200)`), then `PageRequest.of(p, s, parseSort(sort))`. |
| `Sort parseSort(String sort)` | Parses `"field,dir"`. Blank → `Sort.unsorted()`. Splits on `,`; `parts[0]` is the property; direction is `DESC` only if `parts[1]` equals `"desc"` (case-insensitive), else `ASC`. |

**Why it matters.** The `MAX_SIZE` clamp is a DoS guard — a client cannot request `size=1000000` and force a huge page. **Gotcha:** `parseSort` does *no* allow-listing of the property name; it trusts the caller to bind to a real entity field, and an unknown property surfaces later as a Spring Data `PropertyReferenceException` (→ handled generically). Controllers that accept arbitrary sort fields should consider validating against a known set.

## `PagedResponse.java`

**Responsibility.** The uniform JSON envelope for paginated list endpoints, decoupling the wire format from Spring Data's `Page` (whose default JSON serialization is verbose and unstable across versions).

**Record:** `PagedResponse<T>(List<T> items, long total, int page, int size)`

- `from(Page<T> p)` — maps `getContent()/getTotalElements()/getNumber()/getSize()`.
- `of(items, total, page, size)` — manual construction (e.g. for native/projected queries that don't return a `Page`).

Consumed by list controllers across the API; the React admin UI relies on this exact shape.

## `Ids.java`

**Responsibility.** Single source of new primary-key UUIDs. `final` + private ctor.

- `UUID newId()` → `UuidCreator.getTimeOrderedEpoch()` (the f4b6a3 library's **UUIDv7**: time-ordered, epoch-based).

**Why UUIDv7 and not `UUID.randomUUID()` (v4)?** v7 IDs sort by creation time, which keeps B-tree index inserts append-friendly (far less page fragmentation than random v4 PKs) and makes "order by id" approximate "order by created_at". Used everywhere a new entity id is minted, including `IdempotencyInterceptor.Store.insertInFlight`.

## `AuthenticatedUser.java`

**Responsibility.** The object stored as the Spring Security `Authentication` *principal* for both human users and API keys. A `record`, so immutable.

**Record components:**

| Component | Meaning |
|---|---|
| `UUID userId` | The user id (human principal). For an API key this is the key id or null depending on construction. |
| `String email` | Display/identity email. |
| `boolean superAdmin` | If true, `hasAuthority` short-circuits to allow everything. |
| `Set<String> authorities` | The granted authority **codes** (fast `contains` checks). |
| `Collection<? extends GrantedAuthority> grantedAuthorities` | The Spring-typed authorities (for the framework's own checks). |
| `boolean apiKey` | True if this principal is a machine API key. |
| `UUID apiKeyOrgId` | The org an API-key principal is bound to (tenant scope). |

**Constructors.** A 5-arg ctor for human principals delegates to the canonical 7-arg ctor with `apiKey=false, apiKeyOrgId=null` — kept for backward compatibility with call sites predating API-key support.

**Methods.**
- `hasAuthority(String code)` → `superAdmin || authorities.contains(code)`. The **privilege check** used throughout RBAC; the `superAdmin` short-circuit is why a super-admin bypasses individual authority checks.
- `isApiKey()` → the `apiKey` flag (also auto-generated as the record accessor, but the explicit method reads better at call sites).

**Gotcha.** Two parallel authority representations (`Set<String>` and `Collection<GrantedAuthority>`) must be kept consistent by whoever builds the principal (the auth filters); `hasAuthority` only consults the `Set<String>`.

## `SecurityUtils.java`

**Responsibility.** Static convenience accessors over `SecurityContextHolder` so feature code never touches the holder directly. `final` + private ctor.

| Method | Behaviour |
|---|---|
| `Optional<AuthenticatedUser> currentUser()` | Reads the context's `Authentication`; returns empty unless authenticated **and** the principal is an `AuthenticatedUser` (defensive `instanceof` pattern). |
| `UUID currentUserId()` | `currentUser().userId()` or **throws `ApiException.unauthorized`** — for code paths that require a user. |
| `AuthenticatedUser requireUser()` | Same, returns the whole principal or throws 401. |
| `boolean isAuthenticated()` | `currentUser().isPresent()`. |
| `Optional<UUID> currentOrgId()` | The caller's **bound org** — only populated for API-key principals (`filter(isApiKey).map(apiKeyOrgId)`); empty for human users or unauthenticated. |

**Why `currentOrgId` is api-key-only.** A human admin can act across orgs (subject to RBAC); an API key is scoped to exactly one tenant, so its org id is the natural tenant boundary. Tenant-isolation logic uses this. Collaborators: used by `IdempotencyInterceptor.resolveActor()` and `GlobalExceptionHandler.recordDenied`, among many feature services.

---

# Audit / trust context

## `AuditContext.java`

**Responsibility.** A per-request, ThreadLocal scratchpad that carries audit metadata from wherever it is discovered (auth filter, controller, service) to wherever the audit row is finally written (the audit aspect or `GlobalExceptionHandler`). `final` + private ctor; all access is via static methods over a single `ThreadLocal<Ctx>` initialised lazily per thread.

**Inner `Ctx` state:** `action`, `targetType`, `targetId`, `actorUserId`, `actorOrgId`, `ip`, `outcome` (default `SUCCESS`), `failClosed` (default false), `recorded` (default false), and a mutable `payload` map.

**Setters:** `set(action)`, `setTarget(type,id)`, `putPayload(key,value)`, `setActor(userId,orgId)`, `setIp(ip)`, `setOutcome(o)` (null coerced to SUCCESS), `markFailClosed()`, `markRecorded()`.
**Getters:** `currentAction/TargetType/TargetId/ActorUserId/ActorOrgId/Payload/Ip/Outcome`, `isFailClosed()`, `isRecorded()`.
**Lifecycle:** `clear()` → `CTX.remove()`.

**Two sentinels that matter:**

- **`recorded`** — set by an explicit/aspect audit write so a second writer (the aspect *or* the exception-handler fallback) does **not** duplicate the row for the same request. `GlobalExceptionHandler.recordDenied` checks `isRecorded()` first and calls `markRecorded()` after.
- **`failClosed`** — flags that this action's audit write must be transactional (a write failure aborts the business tx). Populated from `AuditProperties.failClosedActions`.

**Critical gotcha — leakage.** Because the store is a `ThreadLocal` on a pooled worker thread, **someone must call `clear()` at end of request** or the next request on that thread inherits stale `recorded`/actor/payload state. `GlobalExceptionHandler.recordDenied` clears it on the error path; the audit interceptor/aspect is responsible for the success path.

## `AuditProperties.java`

**Responsibility.** `@Component @ConfigurationProperties(prefix = "app.audit")` — binds two operator-tunable lists. Self-annotated `@Component` so it binds without `@EnableConfigurationProperties`/scanning on the app class.

| Property | Default | Meaning / consumer |
|---|---|---|
| `app.audit.trusted-proxies` | `[]` | CIDR blocks whose `X-Forwarded-For` is trusted. **Empty ⇒ XFF never trusted** and `getRemoteAddr()` is always used. Consumed by `TrustedProxyResolver`. |
| `app.audit.fail-closed-actions` | `[]` | Action codes whose audit writes must be transactional/fail-closed. Consumed by the audit aspect + high-value call sites. |

Both setters null-coerce to an empty list, so a YAML key present-but-empty can't NPE downstream.

## `TrustedProxyResolver.java`

**Responsibility.** The **single, non-spoofable source of "the real client IP."** Replaces ad-hoc XFF parsing that previously existed in the audit interceptor and the `getRemoteAddr()`-only logic in the JWT auth filter, giving auth, audit, rate limiting and access logging one consistent view of the caller.

**Class:** `@Component public class TrustedProxyResolver`. The ctor compiles `AuditProperties.getTrustedProxies()` into a `List<IpAddressMatcher>` once at startup; an invalid CIDR is logged and skipped (not fatal).

**Method:** `String resolveClientIp(HttpServletRequest req)`

Algorithm:
1. `req == null` → `null` (the only null return).
2. `peer = req.getRemoteAddr()` (the direct socket peer).
3. If there are **no trusted matchers**, or `peer` is null, or **`peer` is not in a trusted block** → return `peer`. (Untrusted callers can't influence the result.)
4. Else read `X-Forwarded-For`; if absent/blank → return `peer`.
5. Else return the **leftmost** XFF token (substring up to the first comma, trimmed); empty → `peer`. Malformed XFF → `peer` (never trust garbage).

`isTrusted(ip)` iterates the matchers; an address-family mismatch (IPv4 matcher vs IPv6 ip) is caught and treated as no-match.

**XFF direction policy (important security caveat, from the class doc).** When the peer is trusted, the **leftmost** (originating-client) token is returned. This is correct for a **single** trusted proxy in front of the app. With *chained* proxies the leftmost token can be spoofed by the original client, so the trusted-proxy list must reflect *every* hop, and operators must understand this is a single-proxy assumption. The safe default (`trusted-proxies` empty) means XFF is ignored entirely and the socket peer wins — you must opt in.

**Collaborators.** Used by `GlobalExceptionHandler` (audit IP), `AccessLogFilter` (`client_ip`), and `RateLimitFilter` (the per-IP bucket key — so each end-user behind the proxy gets their own bucket instead of the whole user base sharing the proxy IP).

---

# Request-lifecycle filters

> All four are `OncePerRequestFilter`s with explicit `@Order`. Because `CorrelationIdFilter` runs first, every later filter's log lines already carry `requestId`. Each writes/cleans up in `finally` so behaviour is correct even when a downstream handler throws.

## `CorrelationIdFilter.java`

**Responsibility.** Distributed-tracing correlation IDs across the `HTTP → DB → outbox → NOTIFY` hops. `@Component @Order(HIGHEST_PRECEDENCE)` so it runs ahead of Spring Security's `FilterChainProxy` and the app's auth filters — every downstream log line shares the id.

**Constants:** `REQUEST_ID_HEADER = "X-Request-Id"`, `MDC_KEY = "requestId"` (referenced by `logback-spring.xml`), `MAX_ID_LENGTH = 64`, and `SAFE_ID` allow-list pattern `[A-Za-z0-9._:-]{1,64}`.

**`doFilterInternal` flow:**
1. `sanitize` the inbound `X-Request-Id`; if absent/blank/failing the allow-list → generate a fresh `UUID.randomUUID()`.
2. `MDC.put("requestId", …)`.
3. Echo it on the **response** header *before* `chain.doFilter` — so the header is present even if a downstream filter commits the response early (e.g. a 401 from auth).
4. `finally { MDC.remove(MDC_KEY); }` — never leak onto a pooled thread.

**`sanitize(raw)`** trims, then returns null unless the value matches `SAFE_ID`. **Why the allow-list:** a client-controlled header flows straight into log lines and the MDC; bounding length and charset prevents log-injection/forging and unbounded values.

## `AccessLogFilter.java`

**Responsibility.** Implements the previously-phantom `app.observability.access-log.enabled` flag — one structured log line per completed request. `@Component @Order(HIGHEST_PRECEDENCE + 10)`, i.e. right after the correlation filter (so `requestId` is bound) and ahead of Spring Security (so even rejected/unauthenticated requests, e.g. a 401, get a line).

**Constructor.** Injects `TrustedProxyResolver` and `@Value("${app.observability.access-log.enabled:true}") boolean enabled`. Dedicated logger name `com.example.cp.access` so operators can route/level it independently.

**`shouldNotFilter(req)`** → skip entirely if disabled (zero overhead), and skip `"/actuator/health*"` so liveness/readiness probes don't flood the log.

**`doFilterInternal`** times with `System.nanoTime()`, calls the chain, and in `finally` logs:
```
access method={} path={} status={} duration_ms={} client_ip={}
```
The `requestId` is **not** duplicated into the message — it's already in the MDC and surfaced by `logback-spring.xml` (bracketed in dev, a JSON field in prod). `path()` prefers `getServletPath()`, falling back to `getRequestURI()`.

## `RequestSizeLimitFilter.java`

**Responsibility.** A JSON-body DoS guard. `@Component @Order(HIGHEST_PRECEDENCE + 5)` — after the correlation filter, **before** the idempotency body-caching filter and Spring Security, so an oversized body is rejected *before* anything buffers or parses it.

**Why it exists.** `spring.servlet.multipart.*` / `tomcat.max-http-form-post-size` only bound *multipart/form* bodies; a large `application/json` POST (e.g. to the unauthenticated `POST /api/v1/auth/login`) would otherwise be fully buffered and Jackson-parsed — a cheap memory/CPU amplification vector.

**Constructor.** `@Value("${app.request.max-body-size:256KB}") DataSize` → `maxBytes`.

**`doFilterInternal`.** For body-bearing methods (POST/PUT/PATCH), if the declared `Content-Length` (`getContentLengthLong()`) `> maxBytes`, call `reject(...)` and stop; else continue.

**`reject(...)`** logs a warn, sets `413 Payload Too Large` with a hand-rolled (all-static, so safe) RFC-7807 problem JSON, sets `Content-Length`, and sets `Connection: close` to drop the socket rather than drain a huge body.

**Gotcha.** Only the *declared* `Content-Length` is checked. **Chunked/streaming requests that omit `Content-Length` pass through here** and remain bounded only by the container's own stream limits — by design, but worth knowing.

## `RateLimitFilter.java`

**Responsibility.** Per-client-IP token-bucket rate limiting for the **sensitive auth endpoints** (login, two-step MFA login, the two password-reset steps). Unlike the other three filters this is **not** auto-registered on the servlet chain; it's wired *inside* the Spring Security chain (before `JwtAuthFilter`) by `SecurityConfig`, and `SecurityConfig` also registers a disabled `FilterRegistrationBean` to suppress Boot's servlet auto-registration.

**Class:** `RateLimitFilter extends OncePerRequestFilter` (plain class, not `@Component`).

**Construction (by `SecurityConfig.rateLimitFilter`):** `new RateLimitFilter(objectMapper, trustedProxyResolver, capacity, refillPerMinute)`, where `capacity` ← `app.ratelimit.auth.capacity` (default 10) and `refillPerMinute` ← `app.ratelimit.auth.refill-per-minute` (default 10).

**Internals:**
- `PROTECTED_PATHS` = the 4 exact auth paths.
- `MAX_BUCKETS = 50_000` — hard cap on tracked per-IP buckets.
- `buckets` — an **access-ordered LRU** `LinkedHashMap` (`new LinkedHashMap<>(256, 0.75f, true)`) whose `removeEldestEntry` evicts once `size() > MAX_BUCKETS`. This bounds memory against IP-spray attacks (an attacker rotating source IPs can't grow the map unboundedly).

**`shouldNotFilter(req)`** — the filter is added broadly, so it self-restricts: skip unless it's a **POST** to one of `PROTECTED_PATHS`.

**`doFilterInternal`:**
1. Key = `proxyResolver.resolveClientIp(request)` (falling back to `getRemoteAddr()` if blank) — *per end-user* bucketing behind a trusted proxy.
2. Under `synchronized (buckets)`, `computeIfAbsent(key, newBucket)`. (The lock guards the non-thread-safe `LinkedHashMap`; the held section is a tiny get/put, negligible vs. the downstream bcrypt/Redis work.)
3. `bucket.tryConsume(1)` → proceed; else `writeTooManyRequests`.

**`newBucket()`** — bucket4j `Bandwidth.classic(capacity, Refill.greedy(refillPerMinute, 1 min))`.

**`writeTooManyRequests`** — `429`, problem JSON via the injected Jackson `ObjectMapper`, and a `Retry-After` header computed as `max(1, 60/refillPerMinute)` seconds.

**Gotchas / scope.** (1) **Per-instance, in-memory** — across a multi-node deployment each node has its own buckets; the *cluster-wide* brute-force lockout is handled separately by `LoginRateLimiter` (Redis-backed in prod). A Redis-backed bucket4j proxy-manager is the noted horizontal-scaling follow-up. (2) The bucket is consumed *before* auth runs, so even a wrong-password attempt costs a token — intentional, that's the point.

---

# Observability

## `ObservabilityConfig.java`

**Responsibility.** Central Micrometer wiring. A dependency-light `@Configuration` referencing only micrometer-core (already pulled in by the actuator starter) + Boot actuator types, and guarded so a slice test with no `MeterRegistry` still starts.

**Beans:**

| Bean | Purpose |
|---|---|
| `MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer()` | Adds the `app=control-panel` common tag (`MeterFilter.commonTags`) to **every** meter — so a shared metrics backend can slice by application. Common-tag (not per-meter) means it applies to all current and future meters uniformly. |
| `TimedAspect timedAspect(MeterRegistry)` | Backs `@Timed` method instrumentation. Guarded by `@ConditionalOnClass(TimedAspect.class)` + `@ConditionalOnBean(MeterRegistry.class)` + `@ConditionalOnMissingBean` so it's only created when metrics are actually present. |
| `MetricsService metricsService(ObjectProvider<MeterRegistry>)` | The named-meter facade; `@ConditionalOnMissingBean`. **Always available** — falls back to a private `SimpleMeterRegistry` when no actuator registry exists, so call sites can inject and increment unconditionally. |

### Inner `MetricsService` (the metrics facade)

`public static final class MetricsService` — owns the **canonical meter names** so the per-flow increments (wired by the owning feature agents) and the dashboards/alert rules stay in lockstep.

Canonical names (keep in sync with dashboards):

| Constant | Meter | Flow |
|---|---|---|
| `OUTBOX_FAILED` | `cp.outbox.failed` | transactional outbox |
| `WEBHOOK_ATTEMPTS` / `WEBHOOK_FAILURES` | `cp.webhook.attempts` / `cp.webhook.failures` | webhook delivery |
| `LICENSES_ISSUED` / `LICENSES_REVOKED` | `cp.licenses.issued` / `cp.licenses.revoked` | license lifecycle |
| `LOGIN_FAILURES` / `LOGIN_LOCKOUTS` | `cp.auth.login.failures` / `cp.auth.login.lockouts` | auth/lockout |
| `REDIS_FALLBACKS` | `cp.redis.fallbacks` | Redis→in-memory fallback |
| `OUTBOX_BACKLOG` | `cp.outbox.backlog` | gauge (pending outbox events) |

Construction resolves the `MeterRegistry` via the `ObjectProvider` (`getIfAvailable()`), falling back to a fresh `SimpleMeterRegistry`; it registers the backlog **gauge** once, bound to an `AtomicLong outboxBacklog`.

Methods:
- `increment(name)` / `increment(name, amount)` — lazily registers (`Counter.builder(name).register(registry)`) and bumps a counter.
- `setOutboxBacklog(long)` — updates the gauge's backing `AtomicLong` (the gauge reads it via `AtomicLong::get`).

**Why a facade + no-op fallback.** Call sites (outbox, webhook, licensing, auth, Redis) inject `MetricsService` and call `increment(...)` with **no metrics-present guard**; when actuator metrics are absent the private `SimpleMeterRegistry` accepts and discards the increment instead of NPEing. Centralising the names here is the whole point — one place defines the contract the dashboards depend on.

---

# Idempotency subsystem (`Idempotency-Key`)

This is the largest sub-area: a complete at-least-once-safe replay mechanism for mutating requests. Five files cooperate.

```
[IdempotencyBodyCachingFilter]  buffers req body (replayable) + wraps resp (cacheable)
        │  (only POST/PUT/PATCH carrying a non-blank Idempotency-Key)
        ▼
[IdempotencyInterceptor.preHandle]   hash body → Store.find(key,method,path,actor)
        │     ├─ completed + same hash      → replay stored status/body, 200/2xx, Idempotency-Replayed: true
        │     ├─ completed + different hash → 422 (key reused for a different request)
        │     ├─ in-flight                  → 409 (originating request still running)
        │     └─ none                       → Store.insertInFlight() (UNIQUE claim) → run handler
        ▼
   @RestController runs (sees the same buffered body)
        ▼
[IdempotencyInterceptor.afterCompletion]
        ├─ ex!=null or status<200 or status>=400 → Store.delete(row)  (release claim; retryable)
        └─ 2xx/3xx                               → Store.complete(row, status, body, ctype, location)
        ▼
[IdempotencyRetentionJob]  hourly Store-independent purge of rows older than TTL
```

## `IdempotencyKey.java`

**Responsibility.** The persisted record of one idempotent mutating request. JPA `@Entity` on table `idempotency_keys`, Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`.

**Natural key:** `(idem_key, method, path, actor_user_id)` — a DB UNIQUE constraint, mirrored by the repository finder. The PK `id` is a separate `UUID`.

**Columns:**

| Field / column | Notes |
|---|---|
| `id` | PK UUID (minted via `Ids.newId()`). |
| `idemKey` (`idem_key`, ≤255) | The client's `Idempotency-Key` header. |
| `method` (≤10), `path` (≤512) | HTTP method + servlet path. |
| `actorUserId` (`actor_user_id`, ≤64) | Caller scope as **text**: human user id, `apikey:<orgId>`, or literal `"anonymous"`. Text so one column covers all principal kinds; part of the unique key so **one caller can never replay another's stored response**. |
| `requestHash` (≤64) | SHA-256 hex of the first request's body → lets a same-key-different-body retry be rejected rather than silently replaying an unrelated response. |
| `responseStatus` | Final HTTP status; **null while in-flight** (the two-phase marker). |
| `responseBody` (`text`) | Captured body for replay. |
| `responseContentType` (≤128) | So a replay reproduces the original `Content-Type` (not a JSON guess). |
| `responseLocation` (≤2048) | So a replay reproduces e.g. a `201 Created`'s `Location`. |
| `createdAt` | Used for TTL expiry. |

**Method:** `isCompleted()` → `responseStatus != null` (the lifecycle predicate).

## `IdempotencyKeyRepository.java`

`@Repository interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID>`.

- `findByIdemKeyAndMethodAndPathAndActorUserId(idemKey, method, path, actorUserId)` — lookup by the natural key (derived query).
- `@Modifying @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :threshold") int deleteExpired(threshold)` — bulk purge for the retention job.

## `IdempotencyConfig.java`

**Responsibility.** Wires the whole subsystem. `@Configuration implements WebMvcConfigurer`, `@EnableConfigurationProperties(IdempotencyProperties.class)`.

**Constructor.** Injects `IdempotencyInterceptor.Store` (a standalone `@Component`, so its transactional proxy is honoured) and `IdempotencyProperties`, and **eagerly builds** `new IdempotencyInterceptor(store, Clock.systemUTC(), properties.getTtl())`. Building it in the ctor (rather than relying on a separately-managed bean for registration) guarantees `addInterceptors` always sees a fully-initialised instance and avoids a self-referential cycle with the configurer.

**Beans / overrides:**
- `@Bean idempotencyInterceptor()` — also exposes the interceptor as a bean (handy for tests).
- `addInterceptors(registry)` — registers it on `/api/**`.
- `@Bean idempotencyBodyCachingFilter()` — a `FilterRegistrationBean<IdempotencyBodyCachingFilter>` on `/api/*`, `order = HIGHEST_PRECEDENCE + 10` (just below the correlation filter, above Spring Security) so the wrappers are in place before MVC/the interceptor run.

**Inner classes:**

- **`IdempotencyBodyCachingFilter`** (`OncePerRequestFilter`). `shouldCache` = POST/PUT/PATCH **and** a non-blank `Idempotency-Key`. When caching: wrap the request in `CachedBodyHttpServletRequest`, the response in `ContentCachingResponseWrapper`, run the chain, and in `finally` call `wrappedResp.copyBodyToResponse()` (otherwise the client gets an empty body). Non-idempotent traffic passes through unwrapped — **zero overhead**.

- **`CachedBodyHttpServletRequest`** (`HttpServletRequestWrapper`). Reads `getInputStream().readAllBytes()` **once in the constructor** and replays those bytes on every `getInputStream()`/`getReader()`. This is the crucial difference from Spring's `ContentCachingRequestWrapper`, which only caches bytes *as they are consumed* and does **not** replay them downstream — so it wouldn't let *both* the interceptor's `preHandle` hash *and* the controller's argument binding see the full body. `getInputStream()` returns a `ServletInputStream` over a `ByteArrayInputStream` (async `setReadListener` throws `UnsupportedOperationException`). `getCachedBody()` exposes the bytes to the interceptor.

- **`IdempotencyProperties`** (`@ConfigurationProperties("app.idempotency")`). `Duration ttl` (default `P1D`); setter null-coerces to 1 day. The window during which a stored record is replayable / before the sweep purges it.

## `IdempotencyInterceptor.java`

**Responsibility.** The brains of the subsystem — a `HandlerInterceptor` implementing claim/replay/conflict over the natural key. Holds **no per-request mutable state**: the in-flight row id is stashed as a request attribute, so the single shared instance is concurrency-safe. Persistence is delegated to the transactional `Store`.

**Constants:** `HEADER = "Idempotency-Key"`, `REPLAYED_HEADER = "Idempotency-Replayed"`, `MAX_KEY_LENGTH = 255` (matches the column), `ATTR_ROW_ID` (request attribute key).

**Constructor:** `(Store store, Clock clock, Duration ttl)` (ttl null-coerced to 1 day). The injected `Clock` makes TTL expiry testable.

### `preHandle(request, response, handler)`

1. Not POST/PUT/PATCH → `return true` (idempotency off).
2. `key = normalizeKey(header)` — trims; null/blank/over-255 → off (`return true`).
3. Compute `method`, `path = pathOf()`, `actor = resolveActor()`, `requestHash = sha256Hex(readBody())`.
4. `store.find(key, method, path, actor)`:
   - present **and not expired** → `handleExisting(...)` (decides replay / 422 / 409) and short-circuit (`return false`).
   - present **but expired** → `store.delete(id)` so this request can re-claim the key.
5. Try to claim: `store.insertInFlight(...)`, stash the row id as `ATTR_ROW_ID`, `return true` (run handler).
6. On `DataIntegrityViolationException` (a concurrent duplicate won the UNIQUE race): re-`find`; if a live winner exists → `handleExisting`; else the winner rolled back between our re-read and its commit → `writeConflict` (409) and `return false`.

`isExpired(record)` = `createdAt + ttl < now`.

### `afterCompletion(request, response, handler, ex)`

1. If no `ATTR_ROW_ID` UUID attribute → return (we never claimed this request — no header, or a replay short-circuit).
2. **Release vs. complete:** if `ex != null` **or** `status < 200` **or** `status >= 400` → `store.delete(rowId)` and return. The reasoning (verbatim from the code): a 4xx is transient/correctable — caching it would pin a failure for the whole TTL so a corrected retry could never re-execute; a 5xx/exception is likewise transient. Only **2xx/3xx** is durable.
3. Otherwise read the body from the `ContentCachingResponseWrapper` (`getContentAsByteArray()`), capture `Content-Type` and `Location`, and `store.complete(rowId, status, body, contentType, location)`.
4. Any persistence failure: warn + best-effort `store.delete(rowId)` — an idempotency bookkeeping failure must **never mask the real response** nor leave an in-flight row that wedges future retries.

### Replay / conflict helpers

- `handleExisting(record, requestHash, response)`:
  - not completed → `writeConflict` (**409**, originating request still in flight/crashed).
  - completed but **different** request hash → `writeProblem(422, "Idempotency-Key reused", …)`.
  - else → `replay(record, response)`.
- `replay(record, response)` — set the stored status, `Idempotency-Replayed: true`, restore `Location` if present, restore `Content-Type` (prefer the stored one; fall back to JSON only for legacy rows that predate type capture), and write the stored body **through the caching response** (the body-caching filter's `copyBodyToResponse()` commits it and sets `Content-Length`, so it deliberately does **not** flush/commit here).
- `writeConflict` / `writeProblem` — emit hand-rolled RFC-7807 JSON with `application/problem+json`. `jsonString(...)` does manual escaping (`"`, `\`, control chars → `\uXXXX`); the doc notes all fields here are static strings so this is safe (no `ObjectMapper` coupling).

### Other helpers

- `isMutating` — POST/PUT/PATCH.
- `normalizeKey` — trim; reject blank/over-length.
- `pathOf` — servlet path, fall back to URI.
- `resolveActor()` — **scope discipline:** human → `userId`; api-key → `"apikey:" + apiKeyOrgId`; else `"anonymous"`. Keeping callers disjoint stops one caller replaying another's stored response.
- `readBody(request)` — pulls bytes from the `CachedBodyHttpServletRequest` native wrapper (empty array if absent, e.g. body-caching wasn't applied).
- `sha256Hex(body)` — SHA-256 hex via `MessageDigest` (null body → empty; `NoSuchAlgorithmException` → `IllegalStateException`).
- `now()` → `OffsetDateTime.now(clock)`.

### Inner `Store` (transactional persistence boundary)

`@Component public static class Store` — a **standalone bean** (not a `@Bean` of the configurer) so it's a first-class proxied bean whose transaction boundaries are honoured, without a self-referential dependency on the configurer that registers the interceptor.

Every method is `@Transactional(propagation = REQUIRES_NEW)` so claiming/completing a key **commits independently of the business handler's transaction** — a rolled-back handler must not roll back the already-committed in-flight claim, and vice-versa:

| Method | Tx | Behaviour |
|---|---|---|
| `find(...)` | REQUIRES_NEW, readOnly | natural-key lookup |
| `insertInFlight(...)` | REQUIRES_NEW | builds row with `Ids.newId()`, `saveAndFlush` so the UNIQUE violation surfaces **now** (not at a later flush); returns the new id |
| `complete(rowId, status, body, ctype, location)` | REQUIRES_NEW | fills the response fields if the row still exists |
| `delete(rowId)` | REQUIRES_NEW | releases the claim |

**Gotcha for new engineers.** The `REQUIRES_NEW` independence is the whole correctness story: a business handler that throws *after* `preHandle` claimed the key relies on `afterCompletion` to `delete` (release) the claim, because the claim's own transaction already committed and won't be rolled back with the handler. If `afterCompletion` somehow didn't run, the in-flight row would block retries until the TTL/retention sweep removes it.

## `IdempotencyRetentionJob.java`

**Responsibility.** Bound the `idempotency_keys` table. The interceptor only drops an expired row *lazily* when a same-key request happens to land after the TTL; a key that's never retried would otherwise live forever. `@Component`.

**Constructor.** Injects the repository and reads `ttl` from `IdempotencyProperties` (same window after which a record stops being replayable).

**`purgeExpired()`** — `@Scheduled(fixedDelayString = "${app.idempotency.purge.fixed-delay:PT1H}", initialDelayString = "${app.idempotency.purge.initial-delay:PT5M}")` + `@Transactional`. Computes `threshold = now() - ttl`, calls `repository.deleteExpired(threshold)`, logs a count when `> 0`. Any exception is caught and logged (a failed sweep must not crash the scheduler). This is the "real caller" that justifies the otherwise-orphan `deleteExpired` repository method.

**Config knobs:** `app.idempotency.purge.fixed-delay` (default `PT1H`), `app.idempotency.purge.initial-delay` (default `PT5M`), and indirectly `app.idempotency.ttl`.

---

## Cross-cutting config-property reference

| Property | Default | Owner | Effect |
|---|---|---|---|
| `app.audit.trusted-proxies` | `[]` | `AuditProperties` → `TrustedProxyResolver` | CIDRs whose XFF is trusted; empty ⇒ XFF ignored. |
| `app.audit.fail-closed-actions` | `[]` | `AuditProperties` | Actions whose audit writes must be transactional. |
| `app.request.max-body-size` | `256KB` | `RequestSizeLimitFilter` | Max declared `Content-Length` for body-bearing methods. |
| `app.ratelimit.auth.capacity` | `10` | `RateLimitFilter` (via `SecurityConfig`) | Token-bucket size per IP. |
| `app.ratelimit.auth.refill-per-minute` | `10` | `RateLimitFilter` (via `SecurityConfig`) | Tokens replenished per minute; also drives `Retry-After`. |
| `app.observability.access-log.enabled` | `true` | `AccessLogFilter` | Toggles per-request access logging. |
| `app.idempotency.ttl` | `P1D` | `IdempotencyProperties` | Replay window + retention age. |
| `app.idempotency.purge.fixed-delay` | `PT1H` | `IdempotencyRetentionJob` | Sweep interval. |
| `app.idempotency.purge.initial-delay` | `PT5M` | `IdempotencyRetentionJob` | Sweep startup delay. |

## Gotchas worth remembering (package-wide)

- **ThreadLocal hygiene.** Both `MDC` (`CorrelationIdFilter`) and `AuditContext` MUST be cleared per request. The error handler clears `AuditContext` on the failure path; if you add a new terminal path, clear it too — a leaked `recorded` sentinel silently suppresses the *next* request's audit row.
- **Never echo raw exception messages.** `IllegalArgumentException`/`DataIntegrityViolationException` messages are logged, not returned. Caller-facing text must travel via `ApiException`.
- **One IP source.** Always resolve client IP through `TrustedProxyResolver`, never `getRemoteAddr()` directly — otherwise audit/rate-limit/access-log views diverge and XFF spoofing creeps back in.
- **Idempotency only caches success.** A 4xx/5xx releases the claim, so a corrected retry under the same key really re-executes. Don't rely on it to "remember" a client error.
- **Two filter registration styles.** `CorrelationIdFilter`, `AccessLogFilter`, `RequestSizeLimitFilter` are `@Component`s auto-registered on the servlet chain; `RateLimitFilter` is registered *inside* the Security chain and explicitly de-registered from the servlet chain; the idempotency body-caching filter is a `FilterRegistrationBean`. Order numbers are deliberate (see the pipeline diagram).
