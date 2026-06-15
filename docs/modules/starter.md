# Module: `license-verifier-spring-boot-starter`

> Maven artifact: `com.example:license-verifier-spring-boot-starter`
> Java package: `com.example.licenseverifier.spring` (+ `.spring.actuate`)
> Name: *"Spring Boot auto-configuration and `@RequiresPermission` AOP for the license-verifier SDK"*

## Module overview

This module is the **glue layer** between the pure, framework-agnostic offline-verification SDK (`license-verifier`) and a customer's Spring Boot application. The SDK on its own only knows how to *parse and cryptographically verify* a license JWT and a CRL JWS — it has no notion of "where the `.lic` file lives", "how often to re-read it", "how to fetch the CRL on a timer", "what HTTP status to return when a call is denied", or "how to expose a health endpoint". This starter supplies all of that **runtime orchestration** as Spring beans that auto-wire themselves the moment the jar is on a consumer's classpath, configured entirely through `app.license.*` properties.

Concretely, the starter delivers six things:

1. **Zero-code auto-configuration** (`LicenseVerifierAutoConfiguration`) that builds and wires the SDK's `PublicKeyProvider`, `RevocationChecker`, `LicenseVerifier`, plus this module's own `LicenseService`, AOP aspect, exception advice, and actuator endpoint.
2. **A live, scheduled CRL-backed revocation checker** (`CrlRevocationChecker`) that fetches a *signed* CRL over HTTP, caches it, refreshes it on a timer, and — critically — **fails CLOSED** (denies everything) when the CRL is missing, unreachable, stale, or replayed/rolled-back.
3. **An in-memory license holder** (`LicenseService`) that loads the `.lic` from disk at startup, re-reads it on a schedule, and computes a single authoritative `Status` (ACTIVE / READ_ONLY / EXPIRED / REVOKED / NOT_LOADED) with a deliberate precedence ordering.
4. **A declarative permission guard** (`@RequiresPermission` + `RequiresPermissionAspect`) that lets app code annotate methods/types and have calls transparently denied when the license is inactive or lacks a permission.
5. **An HTTP error contract** (`LicensePermissionDeniedException` + `LicensePermissionDeniedAdvice`) that turns a denied call into an RFC-7807 `403 ProblemDetail`.
6. **A Spring Boot Actuator endpoint** (`LicenseEndpoint`, id `license`) that surfaces redacted license status for ops monitoring.

The defining design principle running through every file here is **fail-closed offline enforcement**: because the customer's app runs in *their* Docker container with no live connection to the control panel guaranteed, the CRL is the *only* channel that can deny an already-issued, not-yet-expired license. The starter therefore treats "I can't prove this license is still good" as identical to "this license is revoked."

### How it fits the bigger picture

```
 control-panel-api  ──issues──▶  license.lic (Ed25519 JWT, typ=license+jwt)
        │                                  │ baked into / mounted on
        │ serves signed CRL                ▼
        │ (typ=crl+jwt) at            ┌──────────────────────────────┐
        ▼                             │  Customer Spring Boot app      │
  /api/v1/licenses/crl  ◀──fetch───  │  (sample-docker-app, etc.)     │
                                      │                                │
                                      │  THIS STARTER auto-configures: │
                                      │   PublicKeyProvider (JWKS)     │
                                      │   CrlRevocationChecker ───────┐│
                                      │   LicenseVerifier  ◀──────────┘│  (uses SDK
                                      │   LicenseService               │   license-verifier)
                                      │   @RequiresPermission aspect   │
                                      │   /actuator/license endpoint   │
                                      └──────────────────────────────┘
```

- **Depends on** the `license-verifier` SDK module for *all* crypto: `LicenseVerifier`, `CrlVerifier`, `PublicKeyProvider`, `License`, `RevocationChecker`, `RevocationList`. This starter contains **no** JWS/Nimbus parsing of its own — that boundary is deliberate (see `CrlRevocationChecker`'s class javadoc).
- **Consumed by** `sample-docker-app` (the demo consumer) and, in production, any customer Docker app that adds the starter to its `pom.xml`.
- **Talks back to** `control-panel-api` over HTTP only for the CRL fetch (and optionally for a JWKS refresh URL). License files themselves arrive out-of-band (mounted file).

### Spring Boot starter mechanics (how it activates with zero config)

Spring Boot discovers the auto-configuration via the imports file at
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, whose single line is:

```
com.example.licenseverifier.spring.LicenseVerifierAutoConfiguration
```

This is the modern (Spring Boot 2.7+/3.x) replacement for the legacy `spring.factories` `EnableAutoConfiguration` key. When the consumer's app starts, Boot reads this file, registers `LicenseVerifierAutoConfiguration`, and that class's `@Bean` methods create everything else. The consumer writes **no Java** — they only set `app.license.*` properties.

### Dependency posture (from `pom.xml`)

| Dependency | Scope | Why it matters to the runtime |
|---|---|---|
| `com.example:license-verifier` | compile | The SDK. All crypto + the `RevocationChecker`/`License` contracts. |
| `spring-boot-starter`, `spring-boot-autoconfigure` | compile | Auto-config + `@ConfigurationProperties`. |
| `spring-boot-starter-aop` | compile | AspectJ for `@RequiresPermission`. |
| `spring-web` | **optional** | `ProblemDetail`/`@RestControllerAdvice` for the 403 contract. Consumers that have no web layer can omit it. |
| `spring-boot-starter-actuator` | **optional** | The `/actuator/license` endpoint. Gated by `@ConditionalOnClass(Endpoint.class)`. |
| `spring-boot-configuration-processor` | optional | Generates IDE metadata for the `app.license.*` keys. |
| `lombok` | provided | Used only by the SDK's `License` (`@Value`/`@Builder`); not by starter code directly. |

Because `spring-web` and actuator are **optional**, the starter degrades gracefully: an app with no web stack still gets license enforcement, just without the HTTP 403 advice or the actuator endpoint (those beans simply aren't registered).

---

## Configuration properties — the consumer's entire API surface

### `LicenseProperties` — `src/main/java/.../spring/LicenseProperties.java`

**Responsibility.** A `@ConfigurationProperties(prefix = "app.license")` POJO that captures every knob a consumer can turn. It is the single source of truth for runtime behavior; every other bean reads from it. Plain getters/setters (no Lombok here), so Spring's relaxed binding maps kebab-case YAML keys (`app.license.crl-url`) onto camelCase fields (`crlUrl`).

**Properties (with defaults and meaning):**

| Property (`app.license.*`) | Field / type | Default | What it does / why it exists |
|---|---|---|---|
| `path` | `String path` | `/etc/app/license.lic` | Filesystem path to the `.lic` file. Default targets a conventional Docker mount point. Read by `LicenseService.readAndVerify()`. |
| `audience` | `String audience` | *(none)* | **Required** `aud` claim the license must carry. Maps to `LicenseVerifier.Builder.audience(...)`. Identifies *which* deployment a license is for (e.g. `docker-app-prod`). If unset, the SDK's `LicenseVerifier` constructor throws (audience is `requireNonNull`). |
| `issuer` | `String issuer` | *(none)* | Optional expected `iss`. When set, enforced on **both** licenses and the CRL (it's passed to `CrlVerifier` too). |
| `refreshFromUrl` | `String refreshFromUrl` | *(none)* | Optional JWKS URL. If set, keys are fetched live (auto-rotation-aware). If null/empty, JWKS is loaded once from `classpath:/jwks.json`. |
| `refreshInterval` | `Duration refreshInterval` | `PT24H` (24h) | How often to (a) re-read the `.lic` and (b) refresh JWKS when a URL is configured. Drives `LicenseService.reload()`'s `@Scheduled`. |
| `clockSkew` | `Duration clockSkew` | `PT5M` (5m) | Tolerance applied to `exp`/`nbf`. Used by the SDK verifier *and* by `LicenseService.isExpired()`. |
| `readOnlyOnExpiry` | `boolean readOnlyOnExpiry` | `true` | When true, an expired-but-otherwise-valid license keeps the app alive in READ_ONLY (mutations blocked, reads allowed). When false, expiry is a hard rejection. |
| `strict` | `boolean strict` | `true` | When true, refuse to start the context if the license is missing/invalid, **and** refuse to start if no CRL URL is set (revocation can't be disabled silently). When false, degrade to NOT_LOADED / `RevocationChecker.none()` with warnings. |
| `crlUrl` | `String crlUrl` | *(none)* | Absolute URL of the **signed** CRL endpoint (e.g. `https://control-panel/api/v1/licenses/crl`). Null/blank ⇒ revocation disabled (or startup failure if `strict`). |
| `crlRefreshInterval` | `Duration crlRefreshInterval` | `PT15M` (15m) | How often to re-fetch the CRL. Drives `CrlRevocationChecker.scheduledRefresh()`. |
| `crlMaxStale` | `Duration crlMaxStale` | `PT1H` (1h) | Grace period **past the CRL's own `nextUpdate`** before the cached list is treated as stale and the checker fails closed. |
| `crlFailClosed` | `boolean crlFailClosed` | `true` | When true, an initial CRL fetch failure aborts startup; when false, startup continues but the checker is non-operational (still deny-all until a CRL loads). |

**Gotchas a new engineer must know:**

- **Two distinct "stale/closed" behaviours are conflated by name but differ in scope.** `crlFailClosed` only governs the *initial* fetch at `load()` time (abort startup vs. continue). The ongoing *deny-all-when-stale* behaviour is **always on** and is governed by `crlMaxStale` — there is no flag to make a *running* app fail *open* on a stale CRL. (See `CrlRevocationChecker.isRevoked`/`isOperational`, which never consult `crlFailClosed`.)
- **`strict` does double duty.** It controls *both* license-load strictness (`LicenseService.load()`) *and* the "you must configure a CRL" guard (`revocationChecker` bean). Setting `strict=true` with no `crl-url` is an intentional hard failure, not a bug.
- Defaults are tuned for production-by-default: `strict=true`, `crlFailClosed=true`, `readOnlyOnExpiry=true`. A consumer must *explicitly* opt out of safety.

---

## Auto-configuration — the wiring root

### `LicenseVerifierAutoConfiguration` — `src/main/java/.../spring/LicenseVerifierAutoConfiguration.java`

**Responsibility.** The single `@AutoConfiguration` class Boot loads from the imports file. It is annotated `@EnableConfigurationProperties(LicenseProperties.class)` (binds `app.license.*`) and `@EnableScheduling` (activates the `@Scheduled` reload/refresh methods elsewhere in the module). Every bean is `@ConditionalOnMissingBean`, so a consumer can override **any** piece by declaring their own bean of the same type.

**Bean graph it builds (in dependency order):**

```
LicenseProperties (from @EnableConfigurationProperties)
        │
        ▼
PublicKeyProvider  licenseKeyProvider(props)
        │  (shared by BOTH the verifier and the CRL verifier)
        ├───────────────┐
        ▼               ▼
RevocationChecker   (CrlVerifier built inline)
 revocationChecker(props, keyProvider)
        │
        ▼
LicenseVerifier  licenseVerifier(props, keyProvider, revocationChecker)
        │
        ▼
LicenseService   licenseService(verifier, props, revocationChecker)   ──▶ svc.load()
        │
        ├──▶ LicensePermissionDeniedAdvice (always)
        ├──▶ RequiresPermissionAspect      (nested AspectConfiguration, @ConditionalOnClass(Aspect.class))
        └──▶ LicenseEndpoint               (nested ActuatorConfiguration, @ConditionalOnClass + @ConditionalOnProperty)
```

**Methods / beans in detail:**

- **`PublicKeyProvider licenseKeyProvider(LicenseProperties props)`**
  - *What it does.* Builds the JWKS provider once. If `refresh-from-url` is set, it parses it to a `java.net.URL` (via `new URI(...).toURL()`) and returns `PublicKeyProvider.fromJwksUrl(url, refreshInterval)` (a live, self-refreshing provider with its own daemon thread). Otherwise it loads `classpath:/jwks.json` via `getResourceAsStream` and returns a static provider.
  - *Why a single shared bean.* The javadoc spells out the intent: the `LicenseVerifier` **and** the CRL `CrlVerifier` must verify against the *same* key set. Sharing one provider avoids (a) two independent JWKS refresh threads and (b) **key-set drift** right after a control-panel key rotation, where the license could verify under a new key while the CRL still verified under the old (or vice versa).
  - *Edge cases / failure modes.* A malformed `refresh-from-url` ⇒ `IllegalStateException("Invalid license.refresh-from-url: ...")`. A missing `classpath:/jwks.json` ⇒ `IllegalStateException("JWKS resource not found on classpath: /jwks.json")`. An `IOException` reading the resource ⇒ wrapped `IllegalStateException`. All of these abort context startup.
  - *Gotcha.* The static-classpath path requires a `jwks.json` baked into the consumer jar that matches the control panel's signing key. The integration tests sidestep this by overriding the bean (`TestKeysConfig.licenseKeyProvider`) with a per-run key — a pattern any consumer can copy when they want to inject keys programmatically.

- **`RevocationChecker revocationChecker(LicenseProperties props, ObjectProvider<PublicKeyProvider> keyProvider)`**
  - *What it does.* This is the **fail-closed gate**. Logic:
    1. If `crl-url` is null/blank **and** `strict=true` → throw `IllegalStateException` with a long, deliberately educational message: revocation is the *only* offline channel that can deny a revoked license, so the app refuses to boot rather than silently ship an un-revocable deployment. (Operator must set `crl-url` or explicitly `strict=false`.)
    2. If `crl-url` is null/blank **and** `strict=false` → log a `WARN` ("license REVOCATION CHECKING IS DISABLED") and return `RevocationChecker.none()` (the SDK no-op that never revokes, always operational).
    3. Otherwise → construct a `CrlVerifier(keyProvider.getObject(), props.getIssuer())`, wrap it in a `CrlRevocationChecker(crl, props)`, call `checker.load()` (synchronous first fetch — may throw and abort startup when fail-closed), and return it.
  - *Why `ObjectProvider<PublicKeyProvider>`.* Using `ObjectProvider` + `.getObject()` defers actually obtaining the key-provider bean until it's needed (and only on the CRL path). On the no-CRL paths, the key provider is never dereferenced here.
  - *Edge cases.* `checker.load()` is what couples "CRL unreachable at boot" to "startup aborts" — but only when `crlFailClosed=true` (see `CrlRevocationChecker.load`).

- **`LicenseVerifier licenseVerifier(props, keyProvider, revocationChecker)`**
  - *What it does.* Assembles the SDK verifier via its builder: `.audience(...)`, `.clockSkew(...)`, `.publicKeys(keyProvider)`, `.revocationChecker(revocationChecker)`, and `.issuer(...)` only when issuer is non-empty (the SDK treats a null issuer as "skip issuer check").
  - *Why the revocation checker is injected here too.* The SDK verifier itself enforces revocation **at verify time** (`LicenseVerifier.checkRevocation`): a non-operational checker makes `verify()` throw `LicenseRevokedException`, and a jti already on the CRL is rejected at load. So the same checker enforces both *at load* (via the verifier) and *post-load* (via `LicenseService.status()`).

- **`LicenseService licenseService(verifier, props, revocationChecker)`**
  - Constructs the service and **calls `svc.load()` eagerly** during context startup so the license is read (and, in strict mode, validated) before the app serves traffic.

- **`LicensePermissionDeniedAdvice licensePermissionDeniedAdvice()`** — always registered (it's `@RestControllerAdvice`; harmless when there's no web layer because nothing throws into MVC).

- **Nested `@AutoConfiguration static class AspectConfiguration`** — `@ConditionalOnClass(Aspect.class)`. Registers the `RequiresPermissionAspect(licenseService)` bean only when AspectJ is present. (It always is, since `spring-boot-starter-aop` is a compile dependency, but the guard keeps the module robust if AOP is excluded.)

- **Nested `@AutoConfiguration static class ActuatorConfiguration`** — `@ConditionalOnClass(Endpoint.class)` **and** `@ConditionalOnProperty(prefix="management.endpoint.license", name="enabled", havingValue="true", matchIfMissing=true)`. Registers `LicenseEndpoint(licenseService)`. The `matchIfMissing=true` means the endpoint is **on by default** when actuator is on the classpath; a consumer disables it with `management.endpoint.license.enabled=false`.

**Collaborators:** reads `LicenseProperties`; constructs/consumes SDK `PublicKeyProvider`, `CrlVerifier`, `RevocationChecker`, `LicenseVerifier`; constructs this module's `CrlRevocationChecker`, `LicenseService`, `RequiresPermissionAspect`, `LicensePermissionDeniedAdvice`, `LicenseEndpoint`. **Called by:** Spring Boot's auto-configuration machinery (via the imports file).

**Gotcha (bean overrides):** Every bean is `@ConditionalOnMissingBean`. The integration test's `TestKeysConfig` exploits exactly this to substitute a `PublicKeyProvider`. Real consumers can do the same to, e.g., supply keys from a secrets manager instead of `jwks.json`.

---

## The fail-closed revocation engine

### `CrlRevocationChecker` — `src/main/java/.../spring/CrlRevocationChecker.java`

**Responsibility.** A `RevocationChecker` (the SDK interface) backed by a live, signed CRL fetched over HTTP from `app.license.crl-url`. It owns the **fetch → verify → cache → schedule** loop and the **fail-closed / monotonicity** guarantees. By design it touches **only** verifier-lib types (`CrlVerifier`, `RevocationList`); all JWS/Nimbus parsing stays inside `CrlVerifier` in the SDK so the trust boundary lives in one place.

**Fields:**
- `CrlVerifier crlVerifier` — does the actual signature/typ/issuer verification of the CRL body, returning a `RevocationList`.
- `LicenseProperties properties` — for `crlUrl`, `crlMaxStale`, `crlFailClosed`.
- `HttpClient httpClient` — a JDK `HttpClient` with a 10s connect timeout, built in the constructor.
- `AtomicReference<RevocationList> current` — the cached list; atomically swapped so reads (`isRevoked`/`isOperational`) never see a torn state and never block.

**Methods:**

- **`load()`** — one-shot startup fetch.
  - Calls `refresh()`. If `refresh()` throws **and** `crlFailClosed=true`, it logs an error and **rethrows** so `LicenseVerifierAutoConfiguration` aborts context startup (the app never serves traffic without a usable CRL). If `crlFailClosed=false`, it logs and swallows — the app starts but the checker is non-operational (deny-all) until a later refresh succeeds.
  - *Verified by:* `CrlRevocationCheckerTest.load_fails_closed_aborts_when_initial_fetch_fails_and_fail_closed_true` and `..._fail_closed_false`.

- **`scheduledRefresh()`** — `@Scheduled(fixedDelayString="${app.license.crl-refresh-interval:PT15M}", initialDelayString=...same...)`.
  - Calls `refresh()` and **always swallows** failures (only WARNs), keeping the previously cached CRL. A transient control-panel outage must not flip a healthy app into deny-all *as long as the cache is still within `nextUpdate + maxStale`*. (Once it goes stale, `isOperational()`/`isRevoked()` independently fail closed.)
  - *Note:* `initialDelay == fixedDelay`, so the first scheduled run is one interval after startup — the *very first* fetch is the synchronous `load()`, not this scheduler.

- **`refresh()`** — the core fetch/verify/swap. Step by step:
  1. Build a `GET` `HttpRequest` to `crl-url` with a 15s request timeout; send it for a `String` body.
  2. Reject non-2xx with `IllegalStateException("CRL endpoint returned HTTP <code>")`.
  3. `crlVerifier.verify(body)` → a `RevocationList` (this enforces EdDSA, `typ=crl+jwt`, known `kid`, signature, and issuer match).
  4. **Monotonicity guard** via `isRollback(previous, fetched)`: if the freshly-fetched CRL is *older* than the cached one (or can't prove it's newer), **reject it** and keep the cached list. WARNs and returns without swapping.
  5. Otherwise atomically `current.set(rl)` and DEBUG-log issuer/issuedAt/nextUpdate/revokedCount.
  - **Failure handling is asymmetric and deliberate:**
    - `InterruptedException` → restore interrupt flag; rethrow as `IllegalStateException("CRL fetch interrupted")` **only if nothing is cached yet** (initial-failure signal), else WARN and keep cache.
    - `RuntimeException` → rethrow **only if `current.get()==null`** (initial failure), else WARN and keep cache.
    - other `Exception` (e.g. `IOException` from the HTTP send) → wrap+rethrow **only if nothing cached**, else WARN and keep cache.
  - The unifying rule: **a failure during the *initial* fetch propagates** (so `load()` can decide to abort startup); a failure *after* a CRL has been cached is non-fatal (keep the last-known-good list until it goes stale).
  - *Verified by:* `refresh_failure_retains_previous_crl`, `accepts_newer_crl_on_refresh`, `rejects_rollback_to_older_crl`.

- **`private static boolean isRollback(RevocationList cached, RevocationList fetched)`** — the **replay/rollback defense**.
  - Returns `false` (not a rollback) when there's no cached list or the cached list has no `issuedAt` (nothing to compare against).
  - Returns `true` when the fetched list has **no `issuedAt`** but a cached one with a known `issuedAt` exists — a CRL that *can't prove* it's newer is treated as a rollback.
  - Returns `true` when `fetched.issuedAt()` is strictly before `cached.issuedAt()`.
  - *Why it exists (security).* A MITM, a caching proxy, or a stale mirror could replay a **validly-signed but older** CRL that omits a recently-revoked jti, silently suppressing that revocation until the cached list happens to go stale. Signature verification alone does **not** stop this (the old CRL was legitimately signed). The `issuedAt` monotonicity check is the defense. The threat model and the exact scenario are documented inline.

- **`isRevoked(String jti)`** (interface impl) — the deny decision the SDK verifier and `LicenseService` both call:
  - No CRL cached (`current==null`) → **`true`** (never loaded ⇒ fail closed, deny all).
  - Cached CRL stale (`rl.isStale(now, crlMaxStale)`) → **`true`** (stale ⇒ fail closed, deny all).
  - Otherwise → `rl.isRevoked(jti)` (true iff this jti is in the revoked set).
  - *Subtlety.* Staleness is evaluated **on every call** against `Instant.now()`, so a cache that was fine a minute ago can start denying without any refresh having run — there is no background "expire" task; freshness is computed lazily at read time.

- **`isOperational()`** (interface impl) — `true` iff a CRL is cached *and* not stale. The SDK's `LicenseVerifier.checkRevocation()` throws `LicenseRevokedException` whenever this is `false`, so a non-operational checker makes **every** `verify()` fail closed — which is why a 503 CRL endpoint at boot ends up with `LicenseService` in NOT_LOADED even for an otherwise-valid license (see `LicenseVerifierAutoConfigurationTest.crl_never_loaded_denies_all`).

**Concurrency notes.** All shared state is a single `AtomicReference`; the scheduler thread writes, request threads read. No locks, no blocking on the read path — important because `isRevoked` is on the hot path of every guarded call. The `HttpClient` is thread-safe and reused.

**Collaborators:** wraps SDK `CrlVerifier`; produces/holds SDK `RevocationList`; reads `LicenseProperties`. **Called by:** the SDK `LicenseVerifier` (at verify/load time) and `LicenseService.status()` (post-load); constructed and `load()`-ed by `LicenseVerifierAutoConfiguration.revocationChecker(...)`; `scheduledRefresh()` driven by Spring scheduling.

**The fail-closed truth table:**

| State of the CRL cache | `isOperational()` | `isRevoked(anyJti)` | Effect on guarded calls |
|---|---|---|---|
| Never loaded (boot fetch failed, `failClosed=false`) | false | true | All denied |
| Loaded, fresh, jti not listed | true | false | Allowed |
| Loaded, fresh, jti listed | true | true | That jti denied |
| Loaded but stale (past `nextUpdate + maxStale`) | false | true | All denied |
| Older CRL replayed (rollback) | unchanged (keeps newer) | unchanged | Newer revocations preserved |

---

## The license state machine

### `LicenseService` — `src/main/java/.../spring/LicenseService.java`

**Responsibility.** The in-memory authority on "what license is active and what may it do." It loads the `.lic` from disk on startup, re-reads it on the refresh interval, holds the parsed `License` in an `AtomicReference`, and — most importantly — collapses license state into a single `Status` enum with a carefully ordered precedence. Every permission check ultimately routes through this service.

**`enum Status`:** `ACTIVE`, `EXPIRED`, `NOT_LOADED`, `READ_ONLY`, `REVOKED`. (Note this is the *starter's* status, distinct from the SDK `License.Status` which only has ACTIVE/EXPIRED/NOT_YET_VALID and does not model revocation or read-only.)

**Fields:** `LicenseVerifier verifier`, `LicenseProperties properties`, `RevocationChecker revocationChecker` (defaults to `RevocationChecker.none()` if the constructor is passed null), `AtomicReference<License> current`.

**Methods:**

- **`load()`** — initial startup load.
  - Calls `readAndVerify()`, stores the result, INFO-logs plan/expiry/permission-count (each via a `safe*` helper that swallows exceptions and returns a sentinel).
  - On any exception: if `strict=true` → rethrow as `IllegalStateException` (abort startup). If `strict=false` → ERROR-log and leave `current` null (status becomes NOT_LOADED; *all permission checks will fail*).
  - *Verified by:* `active_when_valid_license_loaded`, `not_loaded_when_file_missing_and_not_strict`, `strict_load_aborts_when_file_missing`.

- **`reload()`** — `@Scheduled(fixedDelayString="${app.license.refresh-interval:PT24H}", initialDelayString=...same...)`.
  - Re-reads + re-verifies; on success swaps `current`; on **any** failure it **keeps the previously cached license** (only ERROR-logs). This is the "don't break a running app because a transient re-read failed" rule — the inverse of `load()`'s strict abort.
  - *Subtlety.* Unlike the CRL refresh, this does not itself refresh JWKS — JWKS refresh (when a URL is configured) is owned by the `PublicKeyProvider`'s own daemon thread. The javadoc's "and, if configured, refresh JWKS" describes the *combined* effect across both schedulers, not a call this method makes.

- **`readAndVerify()`** (private) — the load/verify core:
  - `Path.of(properties.getPath())`; if the file doesn't exist → `LicenseException("License file not found...")`.
  - `Files.readString(licPath)` then **branches on `readOnlyOnExpiry`**:
    - `true` → `verifier.verifyAllowingExpired(contents)` — signature, audience, issuer, `nbf`, and **revocation** are still fully enforced; only a *present-but-past* `exp` is tolerated (a missing `exp` is still rejected by the SDK). This is what lets a container restart *after* expiry establish the READ_ONLY grace instead of failing closed.
    - `false` → `verifier.verify(contents)` — expiry is a hard rejection.
  - *Why the branch matters (the key insight).* If expiry weren't tolerated at load: under `strict=true` the app would refuse to boot after the license expires (catastrophic for a paying customer who is merely late renewing), and under `strict=false` it would sit in NOT_LOADED and deny *everything* including reads. Tolerating expiry at load lets `status()` then downgrade to READ_ONLY. *Verified by:* `expired_license_loads_as_read_only_at_startup_when_read_only_on_expiry` and the disabled-flag counterpart `expired_license_not_loaded_when_read_only_on_expiry_disabled_and_not_strict`.

- **`current()`** — returns the cached `License` or throws `LicenseException("License is not loaded")`. Used by the aspect after it has already confirmed status is ACTIVE/READ_ONLY.

- **`currentOptional()`** — `Optional<License>` view (used by the actuator endpoint, which must not throw).

- **`isReadOnly()`** — convenience: `status() == READ_ONLY`.

- **`status()`** — **the precedence engine**, evaluated fresh on every call:
  1. `current == null` → `NOT_LOADED`.
  2. **Revocation wins over everything else:** if `!revocationChecker.isOperational()` **OR** (`lic.jti() != null && revocationChecker.isRevoked(jti)`) → `REVOKED`.
  3. Else if `isExpired(lic)` → `READ_ONLY` (when `readOnlyOnExpiry`) or `EXPIRED` (when not).
  4. Else → `ACTIVE`.
  - *Why REVOKED is checked first (security ordering).* This covers a license revoked **after** it was loaded (the control panel adds the jti to the CRL while the app is already running; `verify()` only catches a jti revoked *before* load). It also means a CRL that has gone stale/non-operational (`!isOperational()`) denies *everything* — even an otherwise-ACTIVE or READ_ONLY license — because the app can no longer prove the license *isn't* revoked. *Verified by:* `revoked_wins_over_active` and `revoked_wins_over_read_only_when_checker_goes_non_operational_after_load`.

- **`isExpired(License lic)`** (private) — `exp == null` ⇒ not expired; else `exp.isBefore(now - clockSkew)`. The same `clockSkew` the SDK uses, applied again here so the service's view of expiry agrees with the verifier's.

- **`safePlan` / `safeExpiresAt` / `safePermissionCount`** (private static) — defensive accessors that catch any exception (e.g. a partially-built `License`) and return `"?"` / `null` / `-1`, so logging never throws.

**Concurrency notes.** `current` is an `AtomicReference`; the scheduler thread writes via `reload()`, request threads read via `status()`/`current()`. `status()` makes live calls into the `RevocationChecker`, so its result reflects the *current* CRL freshness at the instant of the call — it is intentionally **not** cached.

**Collaborators:** SDK `LicenseVerifier` (`verify`/`verifyAllowingExpired`), SDK `License`, SDK `RevocationChecker`/`RevocationList` (indirectly via the checker), `LicenseProperties`. **Called by:** `RequiresPermissionAspect` (every guarded call), `LicenseEndpoint` (actuator), and any app code that injects it.

**Status precedence at a glance:**

```
NOT_LOADED  (no license cached)
   │ else
REVOKED     (jti on CRL  OR  CRL non-operational/stale)   ◀── always wins
   │ else
READ_ONLY   (expired AND readOnlyOnExpiry=true)
EXPIRED     (expired AND readOnlyOnExpiry=false)
   │ else
ACTIVE
```

---

## Declarative permission enforcement (AOP)

### `RequiresPermission` (annotation) — `src/main/java/.../spring/RequiresPermission.java`

**Responsibility.** The declarative guard a consumer puts on a method or type. `@Target({METHOD, TYPE})`, `@Retention(RUNTIME)`.

**Attributes:**
- `String value()` (default `""`) — a single permission code that must be present.
- `String[] anyOf()` (default `{}`) — if non-empty, the call is allowed when **any** one of these is granted (and `value` also counts as an additional accepted code).
- `boolean readOnly()` (default `false`) — marks the call as a read operation, allowed in READ_ONLY mode. Defaults to false so **mutating endpoints are blocked once the license expires**.

**Semantics (resolved by the aspect):** Method-level annotations override type-level ones. An empty `value` with empty `anyOf` means "no specific permission required" — the call is allowed for any ACTIVE (or READ_ONLY, if `readOnly=true`) license, useful to gate purely on license *liveness*.

### `RequiresPermissionAspect` — `src/main/java/.../spring/RequiresPermissionAspect.java`

**Responsibility.** The `@Aspect` that intercepts calls to `@RequiresPermission`-annotated methods/types and enforces the guard against `LicenseService`.

**Methods:**

- **`check(ProceedingJoinPoint pjp)`** — `@Around("@annotation(...RequiresPermission) || @within(...RequiresPermission)")`. The `@annotation` pointcut catches method-level annotations; `@within` catches type-level ones. Flow:
  1. `resolveAnnotation(pjp)` — if none resolves, `proceed()` (no guard).
  2. Read `licenseService.status()`. If `NOT_LOADED`, `EXPIRED`, or `REVOKED` → throw `LicensePermissionDeniedException(requiredCode, "License is not active (status=...)")`. **No license access happens** in these states.
  3. If `READ_ONLY` **and** the annotation is *not* `readOnly` → throw (mutating op rejected in read-only mode).
  4. Otherwise (ACTIVE, or READ_ONLY+readOnly): fetch `licenseService.current()` and call `hasAccess(license, rp)`; on failure throw `LicensePermissionDeniedException(requiredCode)`. On success `proceed()`.
  - *Verified by:* the full `RequiresPermissionAspectTest` matrix — ACTIVE allow/deny, anyOf match/deny, NOT_LOADED denies even when the (mocked) license "has" the permission, READ_ONLY denies mutating but allows `readOnly`, REVOKED denies both.

- **`resolveAnnotation(pjp)`** (private static) — precedence: the **method** annotation first; then the **target class** annotation (`pjp.getTarget().getClass()` — the *runtime* proxy target, so type-level annotations on the concrete bean are honored); then the method's **declaring class**. This three-step lookup is what implements "method overrides type."

- **`hasAccess(license, rp)`** (private static) — the matching logic:
  - If `anyOf` is non-empty: allowed if the license has **any** non-empty `anyOf` code, *or* (additionally) the non-empty `value`. Otherwise denied.
  - If `value` is empty (and no `anyOf`): allowed (liveness-only gate).
  - Else: allowed iff `license.hasPermission(value)`.

- **`requiredCode(rp)`** (private static) — builds the human-readable "what was required" string for the exception: `value` if set, else `anyOf` joined with `|`, else `"(unspecified)"`.

**Gotcha (Spring AOP self-invocation).** Because this is proxy-based AOP, an internal call from one method of a bean to another `@RequiresPermission` method on the *same* bean bypasses the proxy and is **not** intercepted — the classic Spring AOP limitation. Guards must be on calls that cross the proxy boundary.

**Collaborators:** `LicenseService` (status + current license), `License.hasPermission`, `LicensePermissionDeniedException`. **Called by:** Spring AOP for every guarded join point. **Registered by:** the nested `AspectConfiguration` in the auto-config.

---

## HTTP error contract

### `LicensePermissionDeniedException` — `src/main/java/.../spring/LicensePermissionDeniedException.java`

**Responsibility.** Unchecked (`RuntimeException`) signal thrown by the aspect when a call is denied. Carries the `missingPermission` string (the required-code description from `requiredCode(rp)`). Two constructors: one builds a default message (`"License does not grant required permission: <code>"`), the other takes an explicit message (used for the status-based denials like `"License is in READ_ONLY mode..."`). Getter `getMissingPermission()` exposes the code to the advice.

### `LicensePermissionDeniedAdvice` — `src/main/java/.../spring/LicensePermissionDeniedAdvice.java`

**Responsibility.** A `@RestControllerAdvice` whose `@ExceptionHandler(LicensePermissionDeniedException.class)` turns a denial into an RFC-7807 `403 ProblemDetail`:
- status `403 FORBIDDEN`, detail = the exception message,
- `type` = `URI("license/permission-denied")`, `title` = `"Permission denied"`,
- a custom property `missingPermission` carrying the denied code.

**Why it exists.** Gives consumer apps a consistent, machine-readable 403 body for license denials without each app writing its own handler. It is `optional`-dependency safe: it only matters when `spring-web` is present (otherwise nothing is throwing into MVC). **Registered by:** `LicenseVerifierAutoConfiguration.licensePermissionDeniedAdvice()` (always, `@ConditionalOnMissingBean`).

**Gotcha.** This advice only catches the exception when it propagates through Spring MVC's controller dispatch. A denial thrown deep in a service called outside a web request (e.g. a scheduled job) surfaces as a raw `LicensePermissionDeniedException`.

---

## Operational visibility

### `LicenseEndpoint` — `src/main/java/.../spring/actuate/LicenseEndpoint.java`

**Responsibility.** A Spring Boot Actuator `@Endpoint(id = "license")` exposing redacted license status at `/actuator/license` (when web-exposed). Lets ops/monitoring see license health without granting access to the raw token.

**Methods:**
- **`info()`** — `@ReadOperation` returning a `LinkedHashMap` (ordered for stable JSON):
  - `status` — `licenseService.status().name()` (always present, even when NOT_LOADED).
  - When a license is present (`currentOptional().ifPresent(...)`): `plan`, `expiresAt`, `jti` (only the **last 8 chars** — see `lastChars`), `permissionsCount`, `featuresCount`, `kid`.
  - Every field is read through a `safe(...)`/`safeSize`/`safeFeatureCount` helper that catches exceptions and yields `null`/`-1`, so the endpoint never 500s on a malformed license.
- **`safe(Callable<T>)`, `safeSize`, `safeFeatureCount`, `lastChars`** (private static) — defensive helpers; `lastChars(s, n)` returns the trailing `n` chars (or the whole string if shorter) and is the **jti redaction** so the endpoint never leaks a full license id.

**Security note.** Only a truncated jti is exposed and the raw JWT is never returned. The endpoint is gated by `@ConditionalOnClass(Endpoint.class)` + `@ConditionalOnProperty(... matchIfMissing=true)` — present by default with actuator, disable via `management.endpoint.license.enabled=false`, and *exposure over HTTP* still requires the standard `management.endpoints.web.exposure.include`.

**Collaborators:** `LicenseService` (status + optional license), SDK `License` accessors. **Registered by:** the nested `ActuatorConfiguration`.

---

## Test sources (behavioural specification)

These tests are the executable contract for the runtime above; they're worth reading as documentation of intent. They live under `src/test/java/.../spring/`.

### `CrlRevocationCheckerTest`
Drives the **real** `CrlRevocationChecker` end-to-end over live HTTP against `StubCrlServer`, exercising the fail-closed revocation runtime the audit (P1-13) found at ~0% coverage. Cases: never-loaded denies all; loads + reports revoked/non-revoked; stale-beyond-max becomes non-operational and denies all; refresh failure retains the previous CRL; rejects rollback to an older CRL; accepts a genuinely newer CRL; `load()` aborts when initial fetch fails and `failClosed=true`; `load()` continues (but stays deny-all) when `failClosed=false`.

### `LicenseServiceStatusTest`
Exercises `LicenseService` load/status transitions against a real `LicenseVerifier`: ACTIVE on valid load; NOT_LOADED on missing file (non-strict); strict-abort on missing file; expired-loads-as-READ_ONLY-at-startup; expired-not-loaded when read-only disabled and non-strict; **REVOKED wins over ACTIVE** (post-load revocation); **REVOKED wins over READ_ONLY** when the checker goes non-operational after load.

### `LicenseVerifierAutoConfigurationTest`
Boots the **real** `LicenseVerifierAutoConfiguration` via `ApplicationContextRunner` against the stub CRL, asserting the fail-closed wiring end-to-end: wires a `CrlRevocationChecker` + ACTIVE license; a revoked jti is rejected at verify ⇒ NOT_LOADED; CRL-never-loaded denies all (and the verifier therefore can't load the license ⇒ NOT_LOADED); `crlFailClosed=true` aborts startup on initial fetch failure (`ctx.hasFailed()`); blank CRL + `strict=true` aborts startup; blank CRL + `strict=false` wires `RevocationChecker.none()` and the license loads ACTIVE. Includes `TestKeysConfig`, which overrides the `licenseKeyProvider` bean (demonstrating the `@ConditionalOnMissingBean` override pattern).

### `RequiresPermissionAspectTest`
Stands up a minimal `@EnableAspectJAutoProxy` context with a mocked `LicenseService`/`License` and a `SampleService` carrying the three annotation shapes. Verifies: allow when permission present; deny when missing (message contains the code); `anyOf` allow/deny; NOT_LOADED denies even when the mock license "has" the permission; READ_ONLY denies a mutating method but allows a `readOnly` one; REVOKED denies both mutating and read-only methods.

### Test fixtures
- **`TestCrypto`** — mints an Ed25519 signing key, exports its public JWKS, and signs `license+jwt` and `crl+jwt` tokens **identical in shape to the control panel's output** (issuer `https://control-panel.example.com`, audience `docker-app-prod`, kid `key-test`). This makes the tests exercise the real SDK crypto, not mocks. Notably the CRL helper sets `iat`/`nextUpdate` as raw epoch-seconds claims — matching the `CrlVerifier`'s tolerance for both `Number` and `Date` representations.
- **`StubCrlServer`** — a tiny in-process `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1:0` serving `/crl`. Its body/status can be swapped between fetches to simulate refresh failures, replays, and rollbacks; it counts GET hits. Serves `Content-Type: application/jwt` on 2xx and an empty body on error codes.

---

## Cross-cutting gotchas & invariants (read before changing anything here)

1. **Fail-closed is the whole point.** "Can't prove the license is good" ≡ "deny." Three independent layers enforce it: the SDK verifier rejects on non-operational checker; `CrlRevocationChecker` returns `isRevoked=true` when stale/unloaded; `LicenseService.status()` returns REVOKED first. Do not "optimize" any of these into fail-open without changing the product's threat model.
2. **One JWKS provider feeds both verifiers.** Keep `licenseKeyProvider` shared between `LicenseVerifier` and `CrlVerifier`, or you reintroduce key-set drift at rotation.
3. **`strict` and `crlFailClosed` are separate axes.** `strict` governs license-load + the "must configure a CRL" rule; `crlFailClosed` governs only the *initial* CRL fetch's effect on startup. Stale-CRL deny-all is unconditional.
4. **Expiry tolerance at load is intentional**, gated by `readOnlyOnExpiry`. Removing `verifyAllowingExpired` would brick a paying customer's app the instant the license lapses.
5. **Two independent schedulers** run (both via `@EnableScheduling`): `LicenseService.reload()` (default 24h) and `CrlRevocationChecker.scheduledRefresh()` (default 15m). The JWKS URL provider runs a *third*, daemon-thread scheduler inside the SDK. Their first scheduled runs are all delayed by one interval; the very first reads happen synchronously during `load()`.
6. **Spring AOP self-invocation** does not trigger `@RequiresPermission`; guard across bean boundaries.
7. **`status()` is computed live**, not cached — each call re-queries the `RevocationChecker`, so a CRL going stale silently flips guarded calls to denied with no event/refresh in between.
