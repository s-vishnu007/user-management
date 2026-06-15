# Module: `sample-docker-app` — the canonical customer integration

> Module overview: `sample-docker-app` is a deliberately tiny Spring Boot 3.3 / Java 21 web service whose only reason to exist is to show a customer **exactly how to consume the license-verifier Spring Boot starter** in their own Dockerized product. It declares one dependency on `license-verifier-spring-boot-starter` (which transitively pulls in the offline `license-verifier` SDK), configures the license/JWKS/CRL through `application.yml`, exposes a handful of demo REST endpoints — some public, some gated by `@RequiresPermission(...)` — surfaces the loaded license through both a public read-only status endpoint and the starter's authenticated `/actuator/license` actuator endpoint, and locks down its actuator surface with HTTP Basic via a hand-written `SecurityConfig`. There is almost no business logic here: the value of the module is the *wiring*, the *configuration surface*, and the *security posture* it demonstrates. Treat every file as a copy‑pasteable template rather than a feature.

This doc reads the whole module (5 Java files + `application.yml`, plus the supporting `Dockerfile`, `pom.xml`, `jwks.json`, `README.md`) and, because the app is mostly glue, also explains the **starter collaborators it leans on** so a new engineer can follow the control/data flow end to end without bouncing between modules.

---

## How it fits the bigger picture

```
 control-panel-api  ──issues──►  license.lic (Ed25519-signed JWT envelope)
        │                                  │  customer downloads + mounts
        │  publishes /.well-known/jwks.json│  at /etc/app/license.lic
        │  publishes /api/v1/licenses/crl  │
        ▼                                  ▼
   (public keys)                  ┌──────────────────────────────┐
        └──────────baked/fetched─►│        sample-docker-app      │
                                  │  ┌────────────────────────┐  │
                                  │  │ license-verifier-starter│  │  ← auto-config
                                  │  │  LicenseService (cache) │  │
                                  │  │  RequiresPermissionAspect│ │
                                  │  │  LicenseEndpoint(actuator)│ │
                                  │  └────────────────────────┘  │
                                  │  PublicController (open)      │
                                  │  ProtectedController (@RequiresPermission)
                                  │  SecurityConfig (actuator Basic)
                                  └──────────────────────────────┘
```

The control panel (`control-panel-api`, documented separately) **issues** the `.lic` file and **publishes** the public JWKS and a signed CRL. This sample app is the *consumer side*: it verifies the license **offline** (no call back to the control panel for the license itself — it only optionally fetches JWKS/CRL over HTTP), then enforces the license's `permissions` set at the endpoint level. It doubles as an **integration smoke-test** for the whole system: issue a license in the control panel, mount it here, hit `/api/license/status`, and you've validated the issuing → signing → verification → enforcement pipeline end to end.

Everything license-related (verification, refresh, revocation, READ_ONLY grace, permission checks, the actuator endpoint) comes from the **starter** via auto-configuration. The sample app contributes only: (1) two controllers, (2) one Spring Security filter-chain config to lock the actuator, (3) `application.yml`, and (4) the `Dockerfile`/`jwks.json` deployment surface.

---

## Module dependency & build surface (`pom.xml`)

`C:\User management\sample-docker-app\pom.xml`

It is a child of the multi-module parent `com.example:user-management-parent:0.1.0-SNAPSHOT`. Declared dependencies and **why each is here**:

| Dependency | Why it's here |
|---|---|
| `spring-boot-starter-web` | Embedded Tomcat + Spring MVC for the `@RestController` demo endpoints. |
| `spring-boot-starter-actuator` | Provides `/actuator/health`, `/actuator/info`, and the host for the starter's custom `/actuator/license` endpoint. |
| `spring-boot-starter-security` | Needed by `SecurityConfig` to put HTTP Basic in front of the actuator. **Note:** adding this starter alone would normally lock down the *whole* app with generated-password Basic auth; `SecurityConfig` is what re-opens `/api/**` and `/actuator/health`. |
| `com.example:license-verifier-spring-boot-starter:${project.version}` | The star of the show. Auto-configures `LicenseService`, `RequiresPermissionAspect`, `LicenseEndpoint`, the CRL `RevocationChecker`, the `LicenseVerifier`, the `PublicKeyProvider`, and the `@ControllerAdvice` that turns denied permissions into RFC-7807 `403`s. Transitively pulls in the offline `license-verifier` SDK (Nimbus JOSE, etc.). |
| `lombok` (optional) | Available but the sample's own classes don't use it; it's `optional` and excluded from the fat jar by the Spring Boot plugin. |
| `spring-boot-starter-test` (test) | Backs `ActuatorSecurityIT`. |

Build config: `finalName=sample-docker-app` (so the fat jar is a stable `target/sample-docker-app.jar` the `Dockerfile` can `COPY` without a version in the name), and the `spring-boot-maven-plugin` excludes Lombok from the repackaged jar.

**Gotcha for new engineers:** the starter is referenced with `${project.version}` (a SNAPSHOT sibling), so a clean build must build the sibling modules first. The `Dockerfile` does exactly this with `mvn -pl sample-docker-app -am` (`-am` = "also make" the required reactor modules).

---

## `SampleApplication.java`

`C:\User management\sample-docker-app\src\main\java\com\example\sample\SampleApplication.java`

**Responsibility:** the Spring Boot entry point — nothing more.

```java
@SpringBootApplication
public class SampleApplication {
    public static void main(String[] args) { SpringApplication.run(SampleApplication.class, args); }
}
```

* **`@SpringBootApplication`** triggers component scanning over `com.example.sample` (picking up `SecurityConfig`, `PublicController`, `ProtectedController`) **and** Spring Boot auto-configuration, which is how the starter's `LicenseVerifierAutoConfiguration` gets activated (it's registered in the starter's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`).
* **Why it exists / what to learn from it:** a customer adds **zero** license code to bootstrap — the starter does everything from `application.yml`. There is intentionally no `@EnableScheduling`, no `@EnableAspectJAutoProxy`, no manual bean wiring here; the starter's auto-config supplies `@EnableScheduling` (for the license `reload()` cron) and the AspectJ aspect bean itself.
* **Collaborators:** none directly; it just boots the context. Everything else is discovered by component scan + auto-config.
* **Gotcha:** because there is no explicit `@EnableAspectJAutoProxy`, the `@RequiresPermission` interception relies on Spring Boot's AOP auto-configuration being on the classpath (it is, transitively via the web + starter deps). If a customer copied only the controllers into a non-Boot app, the annotation would silently do nothing — the aspect must be a Spring bean and AOP proxying must be enabled.

---

## `api/PublicController.java`

`C:\User management\sample-docker-app\src\main\java\com\example\sample\api\PublicController.java`

**Responsibility:** the **unguarded** half of the demo surface, mapped under `/api`. It shows how application code reads license *state* without forcing a permission, and crucially how to do so **defensively** when the license subsystem might not be wired (e.g. the starter disabled, or running in a stripped-down test).

### Class `PublicController`
`@RestController @RequestMapping("/api")`.

**Key field / collaborator:**
```java
private final ObjectProvider<LicenseService> licenseServiceProvider;
```
It injects `LicenseService` through an **`ObjectProvider`**, not directly. This is the single most important teaching point in the file: the controller does **not assume** a `LicenseService` bean exists. `licenseServiceProvider.getIfAvailable()` returns `null` when the bean is absent, and the controller degrades gracefully to an `UNCONFIGURED` response instead of failing context startup or NPE-ing. (Contrast with `ProtectedController`, which hard-depends on the aspect and therefore on the service existing.)

### Methods

| Method | Mapping | Behavior |
|---|---|---|
| `free()` | `GET /api/free` | Returns `{"message":"ok","plan":<plan-or-null>}`. The plan is fetched via the private `currentPlan()` helper, which is null-safe both for a missing service *and* a missing license. Demonstrates "feature works regardless of license, but adapts messaging to the plan." |
| `status()` | `GET /api/license/status` | The license dashboard JSON. Returns an ordered map (`LinkedHashMap`) of `status`, `plan`, `expiresAt`, `permissions`, `features`, `seats`. |
| `currentPlan()` | *(private)* | `service.currentOptional().map(License::getPlan)`, guarded by a `null` service check. |

**`status()` data flow & edge cases — read carefully:**
1. If `getIfAvailable()` is `null` → emits a fully-formed body with `status="UNCONFIGURED"`, `plan=null`, empty `permissions`/`features`. This is a *sentinel* state that does **not** exist in `LicenseService.Status` — it is invented by the controller to mean "the license subsystem itself isn't present." Don't confuse it with `NOT_LOADED` (subsystem present, but no valid license file).
2. Otherwise it calls `service.status().name()` — one of `ACTIVE | EXPIRED | NOT_LOADED | READ_ONLY | REVOKED` (see the starter's `LicenseService.Status`). This is computed live, so it reflects expiry/revocation at request time, not just at load.
3. License-derived fields use `service.currentOptional()` and `Optional.map(...).orElse(...)`, so when a license is present-but-expired-and-tolerated (`READ_ONLY`) you still get the real plan/permissions; when no license is loaded you get empty collections, never `null` collections (except `plan`/`expiresAt`, which are `null`).

**`License` accessors used here:** `getPlan()`, `getExpiresAt()`, `getPermissions()`, `getFeatures()`, `getSeats()`. These are the **Lombok `@Value` getters** on `com.example.licenseverifier.License` (the model also exposes terser fluent aliases like `plan()`/`expiresAt()` used elsewhere — both styles work; the sample uses the JavaBean getters here).

**Why this controller exists:** it is the "tell me about my license" panel a customer's UI would call, and the reference for *reading without enforcing*. It is intentionally **anonymous/open** at the HTTP layer (confirmed open by `ActuatorSecurityIT.public_api_endpoints_stay_open`), because exposing your own plan/permission names to your own app is not sensitive the way the actuator's operational detail is.

**Collaborators:** `LicenseService` (via `ObjectProvider`), `License`. Called by: end users / smoke tests / a customer SPA.

**Gotcha:** `status()` returns the *permission codes the license grants*, i.e. the catalog of what the protected endpoints will allow. It is safe to expose to the app's own users but a customer should think twice before proxying it to *their* end-customers verbatim.

---

## `api/ProtectedController.java`

`C:\User management\sample-docker-app\src\main\java\com\example\sample\api\ProtectedController.java`

**Responsibility:** the **license-gated** half of the demo surface. This is the headline of the whole sample: it shows that *the only thing a customer writes to enforce entitlements is a `@RequiresPermission("code")` annotation* — no `if (license.has(...))` plumbing, no Spring Security roles.

### Class `ProtectedController`
`@RestController @RequestMapping("/api")`. **Stateless** — it injects nothing. Enforcement is entirely declarative via the starter's aspect.

### Methods / endpoints

| Method | Mapping | Guard | Returns |
|---|---|---|---|
| `exportPdf()` | `GET /api/export/pdf` (produces `application/pdf`) | `@RequiresPermission("export.pdf")` | A tiny stub `%PDF-1.4 … %%EOF` byte body. Demonstrates a binary download behind a license. |
| `v2Data()` | `GET /api/v2/data` | `@RequiresPermission("api.v2")` | Sample JSON with a live `Instant.now()` timestamp and a 2-item list. Demonstrates a "premium API tier" behind a feature flag. |
| `invite()` | `POST /api/admin/users/invite` (body `{"email":...}`) | `@RequiresPermission(value = "admin.users.invite")` | `202 ACCEPTED` echoing the email. Demonstrates a **mutating** admin action behind a permission. |

**What actually happens on a request (control flow):**
1. The request hits the Spring MVC mapping. Before the method body runs, the AOP proxy fires `RequiresPermissionAspect.check(...)` (from the starter) because the method carries `@RequiresPermission`.
2. The aspect asks `licenseService.status()`:
   - `NOT_LOADED | EXPIRED | REVOKED` → immediately throws `LicensePermissionDeniedException` (license not active).
   - `READ_ONLY` and the annotation's `readOnly()==false` (the default — none of these three set it) → throws (mutating/standard op rejected while in grace mode).
   - `ACTIVE` (or `READ_ONLY` with `readOnly=true`) → proceeds to the permission check.
3. The aspect then checks `license.hasPermission(code)`; if absent → `LicensePermissionDeniedException(code)`.
4. The starter's `LicensePermissionDeniedAdvice` (`@RestControllerAdvice`) converts that exception into an RFC-7807 **`403 Forbidden`** `ProblemDetail` with `type=license/permission-denied`, `title=Permission denied`, and a `missingPermission` property naming the failed code. The customer gets a clean, machine-readable error — no stack trace, no leak.

So a caller without `export.pdf` on their license gets `403` with `{"missingPermission":"export.pdf", ...}`; with it, they get the PDF. Exactly the README's behavior matrix.

**Why this controller exists / what to learn:**
* It is the proof that **enforcement is one annotation deep**. Note `invite()` is a `POST` (a mutation) — chosen to illustrate that by default such an operation is denied in `READ_ONLY` mode. A read endpoint a customer wanted to keep working past expiry would add `@RequiresPermission(value="...", readOnly = true)`.
* The three permission codes (`export.pdf`, `api.v2`, `admin.users.invite`) are arbitrary strings — they are whatever the control panel mints into the license's `permissions` claim. There is no enum coupling; the codes are a free contract between the issuer and the consumer.

**Edge cases / gotchas:**
* `invite()` null-checks the body and a missing `email` key, returning `""` rather than NPE-ing — defensive demo code.
* Because enforcement is via an AOP proxy, **self-invocation won't be guarded**: if one method in this bean called another annotated method directly (`this.exportPdf()`), the aspect would not fire. None of the demo methods do this, but a customer copying the pattern must know it.
* There is **no** Spring Security authentication on `/api/**` (open by `SecurityConfig.appSecurity`). The license *is* the access-control mechanism here; identity/authn is explicitly out of scope for the demo (a real product would layer its own auth on top).

**Collaborators:** `@RequiresPermission` (starter annotation), enforced by `RequiresPermissionAspect`, errors rendered by `LicensePermissionDeniedAdvice`. Indirectly depends on `LicenseService` (through the aspect).

---

## `SecurityConfig.java`

`C:\User management\sample-docker-app\src\main\java\com\example\sample\SecurityConfig.java`

**Responsibility:** the module's Spring Security configuration. It exists almost entirely to remediate an audit finding (P3: *"sample app exposes the custom license actuator endpoint + health unauthenticated"*). It defines **two ordered filter chains** so that the actuator is authenticated while the functional API and the liveness probe stay open.

### Class `SecurityConfig`
`@Configuration`. Two `SecurityFilterChain` beans.

#### Bean `actuatorSecurity` — `@Order(HIGHEST_PRECEDENCE)`
```java
http.securityMatcher(EndpointRequest.toAnyEndpoint())              // (1) only matches /actuator/**
    .authorizeHttpRequests(a -> a
        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()  // (2)
        .anyRequest().authenticated())                              // (3)
    .httpBasic(Customizer.withDefaults())                          // (4)
    .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))    // (5)
    .csrf(csrf -> csrf.disable());                                 // (6)
```
* **(1)** `EndpointRequest.toAnyEndpoint()` scopes this chain to **only** the actuator endpoints — it will not touch `/api/**`. Because it is `HIGHEST_PRECEDENCE`, it is consulted first; if the request is an actuator request, this chain handles it and the lower chain never sees it.
* **(2)** `health` and `info` are explicitly `permitAll()` so container orchestrators (Docker `HEALTHCHECK`, Kubernetes probes, load balancers) can poll liveness without credentials.
* **(3)** **everything else under `/actuator`** — most importantly the starter's `/actuator/license` (which exposes plan, truncated `jti`, permission/feature counts, signing `kid`) — requires authentication. This is the actual fix: that endpoint leaks entitlement + operational detail and must not be anonymous.
* **(4)** Authentication is **HTTP Basic**, backed by the single in-memory user defined in `application.yml` (`spring.security.user.*`, default `actuator`/`changeit`, role `ACTUATOR`).
* **(5)** **Stateless** sessions — no `JSESSIONID`, no server-side session; appropriate for a credential-per-request actuator and for a containerized app that should not hold session state.
* **(6)** CSRF disabled — safe here because there are no cookies/sessions and the protected actuator calls are non-browser, credentialed API calls.

#### Bean `appSecurity` — `@Order(LOWEST_PRECEDENCE)`
```java
http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
    .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
    .csrf(csrf -> csrf.disable());
```
* The **fallback** chain for every non-actuator request (i.e. `/api/**`). It `permitAll()`s everything: the functional endpoints are intentionally open at the HTTP layer because **their access control is the license, not authentication**. Without this chain, merely having `spring-boot-starter-security` on the classpath would auto-secure the whole app with a generated password and break the demo.
* Also stateless + CSRF-disabled, consistent with a stateless API service.

**Why ordering matters (critical gotcha):** Spring Security walks chains in `@Order`. The actuator chain must come **first** (`HIGHEST_PRECEDENCE`) so its `securityMatcher` claims actuator requests before the catch-all `permitAll` chain can. If the orders were swapped, the `anyRequest().permitAll()` chain would match `/actuator/license` first and the lockdown would silently evaporate. This is exactly the kind of regression `ActuatorSecurityIT` guards against.

**Interaction with `management.endpoint.health.show-details=when-authorized`:** even though `/actuator/health` is `permitAll()`, an *anonymous* caller only sees the top-level `UP`/`DOWN`. Component-level health internals are shown only to an authenticated principal. So health is layered: open for liveness, detailed for operators — defense in depth.

**Collaborators:** consumes `spring.security.user.*` from `application.yml`; protects the starter's `LicenseEndpoint`; complements (does not replace) the `@RequiresPermission` license gate on `/api/**`. Verified by `ActuatorSecurityIT`.

---

## `src/test/java/com/example/sample/ActuatorSecurityIT.java`

`C:\User management\sample-docker-app\src\test\java\com\example\sample\ActuatorSecurityIT.java`

**Responsibility:** a full-stack integration test (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) that locks in the actuator-lockdown contract from `SecurityConfig`. It is the regression guard for the audit P3 fix.

### Class `ActuatorSecurityIT`
Boots the real app on a random port and hits it with a plain `TestRestTemplate` (the `anonymous` field — no credentials by default).

**Test property overrides (`@TestPropertySource`):**
| Property | Why |
|---|---|
| `app.license.strict=false` | Don't fail boot when there's no license file (the test isn't about license loading). |
| `app.license.path=/does/not/exist.lic` | Force a `NOT_LOADED` state deterministically. |
| `app.license.crl-url=` | Blank CRL URL → revocation disabled. Combined with `strict=false`, this avoids the auto-config's "strict requires a CRL URL" startup failure. |
| `spring.security.user.name=actuator` / `password=secret` | Deterministic Basic credentials for the assertions (overrides the yml default `changeit`). |

### Tests (each documents one rule of `SecurityConfig`)

| Test | Asserts |
|---|---|
| `license_endpoint_requires_authentication` | Anonymous `GET /actuator/license` → **401 Unauthorized**. |
| `license_endpoint_accessible_with_basic_auth` | With `actuator:secret` Basic → **200**, body contains `"status"`. |
| `license_endpoint_rejects_wrong_password` | `actuator:wrong` → **401**. |
| `health_probe_stays_open` | Anonymous `GET /actuator/health` → **200**. |
| `public_api_endpoints_stay_open` | Anonymous `GET /api/license/status` → **200**, body contains `NOT_LOADED` (proving the endpoint is reachable *and* the license subsystem is wired but file-less). |

### Nested `@TestConfiguration static class TestKeys`
A crucial, subtle helper. The shipped `classpath:/jwks.json` is **empty** (`"keys": []`), which the verifier's `PublicKeyProvider`/auto-config rejects — so the context could not start. `TestKeys` provides a real `PublicKeyProvider` bean:

```java
@Bean PublicKeyProvider licenseKeyProvider() throws Exception {
    OctetKeyPair key = new OctetKeyPairGenerator(Curve.Ed25519).keyID("test-key").generate();
    String jwks = new JWKSet(List.of(key.toPublicJWK())).toString(true);
    return PublicKeyProvider.fromJwks(new ByteArrayInputStream(jwks.getBytes(UTF_8)));
}
```
It generates a throwaway **Ed25519** key, serializes its *public* JWK, and feeds it to `PublicKeyProvider.fromJwks(...)`. Because this bean is `@ConditionalOnMissingBean` in the auto-config, the test's bean wins and overrides the empty classpath JWKS. No license is ever signed with this key — the security assertions don't need a real loaded license, only a *bootable* context.

**Why this test matters to a new engineer:** it is the executable spec for "what the actuator lockdown must do," and the `TestKeys` trick is the canonical way to make a license-verifier-based Boot context start in tests without a real JWKS/license.

**Collaborators:** the whole app context + `SecurityConfig` + the starter's `LicenseEndpoint`/auto-config + `PublicKeyProvider` (overridden).

---

## `src/main/resources/application.yml`

`C:\User management\sample-docker-app\src\main\resources\application.yml`

**Responsibility:** the single source of truth for runtime configuration, written so that **every meaningful value is overridable by an environment variable** (`${VAR:default}`) — the idiomatic 12-factor pattern for a Docker image. This file *is* the documentation of the starter's configuration surface, by example.

### `server`
* `port: 9090` — fixed; matches the `Dockerfile` `EXPOSE` and `HEALTHCHECK`.

### `spring.application.name` / `spring.security.user`
* `name: sample-docker-app`.
* `spring.security.user.name/password/roles` — the **single in-memory Basic user** consumed by `SecurityConfig.actuatorSecurity`. Defaults `actuator` / `changeit` / role `ACTUATOR`, overridable via `ACTUATOR_USER` / `ACTUATOR_PASSWORD`. The inline comment warns to override `changeit` in any real deployment.

### `app.license.*` — the starter's `LicenseProperties` (prefix `app.license`)
| Key (yml) | Env var / default | Maps to / meaning |
|---|---|---|
| `path` | `LICENSE_PATH` / `/etc/app/license.lic` | Filesystem path of the `.lic` envelope `LicenseService` reads. The `Dockerfile` `VOLUME`s `/etc/app` so a customer mounts the file there. |
| `audience` | `LICENSE_AUDIENCE` / `docker-app-prod` | Required JWT `aud`. Must match what the control panel stamped, or verification fails. |
| `issuer` | `LICENSE_ISSUER` / `https://control-panel.example.com` | Expected `iss`. |
| `refresh-from-url` | `LICENSE_JWKS_URL` / *(empty)* | Optional JWKS URL. **Empty → use the baked `classpath:/jwks.json`.** Non-empty → the auto-config builds a `PublicKeyProvider.fromJwksUrl(...)` that periodically refetches. |
| `refresh-interval` | `LICENSE_REFRESH_INTERVAL` / `PT24H` | ISO-8601 duration. Drives both the license re-read cron (`LicenseService.reload`) and the JWKS refresh cadence. |
| `clock-skew` | `LICENSE_CLOCK_SKEW` / `PT5M` | Tolerance for `exp`/`nbf`. |
| `read-only-on-expiry` | `LICENSE_READ_ONLY_ON_EXPIRY` / `true` | If `true`, an expired-but-otherwise-valid license loads into `READ_ONLY` (app keeps serving read ops) instead of failing closed. |
| `strict` | `LICENSE_STRICT` / **`false` here** | If `true`, refuse to start without a valid license. **Note the override:** the starter's `LicenseProperties` default is `true`, but this yml sets it to `false` so the app boots for local demo without a license. The `Dockerfile`, by contrast, sets `LICENSE_STRICT=true` for production posture. |
| `crl-url` | `LICENSE_CRL_URL` / *(empty)* | Signed-CRL (`typ=crl+jwt`) endpoint. Empty → revocation disabled (`RevocationChecker.none()`) with a loud WARN. **Coupling to remember:** if `strict=true` **and** `crl-url` is blank, the auto-config *refuses to start* — because for an offline product the CRL is the only channel that can deny a revoked license. |

(The starter also supports `crl-refresh-interval`, `crl-max-stale`, `crl-fail-closed` — not surfaced in this yml, defaulted by `LicenseProperties`.)

### `management.*`
* `endpoints.web.exposure.include: health,info,license` — **only** these three actuator endpoints are exposed over HTTP. The custom `license` endpoint is included; nothing else (env, beans, mappings…) is, minimizing attack surface.
* `endpoint.health.show-details: when-authorized` — top-level health is anonymous, details require auth (pairs with `SecurityConfig`).
* `endpoint.license.enabled: true` — switches on the starter's `LicenseEndpoint` bean (auto-config gates it on this property, `matchIfMissing=true`).

### `logging`
* `INFO` for `com.example.licenseverifier` (so license load/reload/WARN lines are visible) and `com.example.sample`.

**Gotchas:**
* The `strict=false` in yml vs `strict=true` in the `Dockerfile` is the most common "why does it behave differently in Docker?" surprise. Local jar runs are lenient; the container is strict (and therefore *also* needs `LICENSE_CRL_URL` set, or it will refuse to boot).
* Leaving `refresh-from-url` empty in production is fine **only if** you replaced the empty baked `jwks.json` — see below.

---

## Supporting deployment files (context, not Java)

### `src/main/resources/jwks.json`
`C:\User management\sample-docker-app\src\main\resources\jwks.json`

Ships **empty on purpose**: `{ "_comment": "Placeholder…", "keys": [] }`. The starter's auto-config will reject an empty key set, so this is a deliberate "you must do something before deploy" tripwire. A customer must either (a) replace this file with the control panel's real JWKS (downloaded from `/.well-known/jwks.json`) baked into the image, or (b) set `LICENSE_JWKS_URL` to fetch it at runtime. The verifier selects the right key by the JWT header `kid`. **This is why `ActuatorSecurityIT` needs `TestKeys`** — without a real key the context won't start.

### `Dockerfile`
`C:\User management\sample-docker-app\Dockerfile`

A multi-stage build that is itself a reference for shipping the consumer app:
* **Builder stage** (`maven:3.9-eclipse-temurin-21`): copies the parent + sibling POMs first for dependency-cache layering, runs a best-effort `dependency:go-offline`, then copies sources for `license-verifier`, `license-verifier-spring-boot-starter`, and `sample-docker-app`, and builds with `mvn -pl sample-docker-app -am -DskipTests package` (`-am` builds the required siblings — necessary because the starter is a SNAPSHOT sibling).
* **Runtime stage** (`eclipse-temurin:21-jre-alpine`): creates a non-root `app` user, declares `VOLUME ["/etc/app"]` (the license mount point), copies the fat jar to `/app/app.jar`, and sets **production-posture** env: `LICENSE_PATH=/etc/app/license.lic`, `LICENSE_AUDIENCE=docker-app-prod`, `LICENSE_ISSUER=…`, and **`LICENSE_STRICT=true`**.
* **Security/ops touches worth copying:** runs as non-root `USER app`; `EXPOSE 9090`; a `HEALTHCHECK` that curls `/actuator/health` (which is anonymous by design — that's *why* health stays open in `SecurityConfig`); `exec java …` as PID 1 for correct signal handling.

**Gotcha:** the image sets `LICENSE_STRICT=true` but does **not** set `LICENSE_CRL_URL`. Per the auto-config rule, a real `docker run` of this image **must** also supply `-e LICENSE_CRL_URL=…` (and a valid mounted license) or the app will refuse to start. The README's example focuses on the license mount; the CRL requirement is the subtle operational catch.

### `README.md`
`C:\User management\sample-docker-app\README.md`

The human-facing quickstart: an endpoint/permission matrix, local `mvn … package` + `java -jar` run instructions, the full `docker build`/`docker run` recipe (mounting `samples/` at `/etc/app`), the env-var table, the "replace the empty JWKS before deploy" warning, and a 5-step smoke-test that ties the sample app back to the control panel (create org → issue license → mount → `curl /api/license/status` → `curl /api/export/pdf`). It is the customer's on-ramp; this doc is the maintainer's deep dive.

---

## End-to-end "what a customer copies" checklist

1. Add the **one** starter dependency (`license-verifier-spring-boot-starter`) — see `pom.xml`.
2. Configure `app.license.*` in `application.yml`, all env-overridable — see `application.yml`.
3. Replace the empty `jwks.json` (or set `LICENSE_JWKS_URL`).
4. Annotate gated endpoints with `@RequiresPermission("code")` — see `ProtectedController`. No other enforcement code is needed; `403`s are rendered for you by the starter's advice.
5. Read license state defensively via `LicenseService` behind an `ObjectProvider` — see `PublicController`.
6. Lock the actuator (especially `/actuator/license`) behind auth while leaving `health`/`info` and your functional API open — see `SecurityConfig`, proven by `ActuatorSecurityIT`.
7. Ship a non-root, health-checked container that mounts the `.lic` at `/etc/app` and runs strict + CRL-enabled in production — see `Dockerfile`.
