# Design spec: api-infra-hardening

## Shared contracts (other files depend on these — keep signatures exact)

### `app.cors.allowed-origins` (config-property)
- **Purpose:** Comma-separated/list CORS allowed origins, read by SecurityConfig.corsConfigurationSource() instead of the hardcoded http://localhost:5173. Env var APP_CORS_ALLOWED_ORIGINS.
- **Signature/contract:**

```
app.cors.allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173}  (bound as List<String> via @Value("${app.cors.allowed-origins}") or comma-split)
```

### `app.auth.expose-reset-token` (config-property)
- **Purpose:** Replaces the System.getProperty("app.auth.expose-reset-token") lookup in AuthController with a real Spring property (#61), gated so it can only be true under a non-prod profile. Bound via @Value into AuthController.
- **Signature/contract:**

```
app.auth.expose-reset-token: ${APP_AUTH_EXPOSE_RESET_TOKEN:false} (boolean). Default false; only set true in application-dev.yml / test profile.
```

### `RateLimitFilter` (bean)
- **Purpose:** New OncePerRequestFilter providing per-client-IP token-bucket rate limiting on sensitive auth endpoints (/api/v1/auth/login, /api/v1/auth/password-reset/**), wired into the Spring Security chain in SecurityConfig before JwtAuthFilter. Uses bucket4j core (already on classpath via bucket4j-spring-boot-starter) with an in-memory ConcurrentHashMap<String,Bucket>.
- **Signature/contract:**

```
package com.example.cp.common; public class RateLimitFilter extends org.springframework.web.filter.OncePerRequestFilter { public RateLimitFilter(ObjectMapper objectMapper, int capacity, int refillPerMinute); protected boolean shouldNotFilter(HttpServletRequest req); protected void doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain); } — returns 429 ProblemDetail (application/problem+json) with Retry-After header when bucket empty.
```

### `app.ratelimit.auth.capacity / app.ratelimit.auth.refill-per-minute` (config-property)
- **Purpose:** Tunable parameters for RateLimitFilter, injected into the @Bean factory in SecurityConfig.
- **Signature/contract:**

```
app.ratelimit.auth.capacity: ${APP_RATELIMIT_AUTH_CAPACITY:10}; app.ratelimit.auth.refill-per-minute: ${APP_RATELIMIT_AUTH_REFILL:10}
```

## File edits

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java`
- depends on: app.cors.allowed-origins, RateLimitFilter, app.ratelimit.auth.capacity / app.ratelimit.auth.refill-per-minute
- SCOPE NOTE: shared file with other themes — apply ONLY these hardening edits; do not touch the filter-wiring for JwtAuthFilter/ApiKeyAuthFilter/SSO or the @Bean structure beyond what is listed.
- 1) Authorization rules in authorizeHttpRequests(...): KEEP the existing public allow-list requestMatchers(...).permitAll() block but REMOVE '/actuator/health/**','/actuator/info' redundancy concerns by making actuator explicit: change the actuator entries so ONLY '/actuator/health','/actuator/health/**','/actuator/info' are permitAll(); ADD a new line '.requestMatchers("/actuator/**").authenticated()' placed AFTER the permitAll() allow-list and BEFORE the '/api/**' matcher so all other actuator endpoints (metrics, prometheus, env, etc.) require authentication.
- 2) Flip the catch-all: replace the final '.anyRequest().permitAll()' with '.anyRequest().authenticated()'. Keep the explicit '.requestMatchers("/api/**").authenticated()' line (now redundant but harmless; may be left for clarity). The Swagger/JWKS/auth/SSO entries already in the permitAll list remain the minimal public surface.
- 3) CORS from config: change the constructor to also accept the allowed origins. Add field 'private final List<String> corsAllowedOrigins;' and constructor param '@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> corsAllowedOrigins' (Spring binds comma-separated env var to List<String>). In corsConfigurationSource() replace 'config.setAllowedOrigins(List.of("http://localhost:5173"))' with 'config.setAllowedOrigins(corsAllowedOrigins)'. (Keep allowCredentials(true); ensure no wildcard origin is ever set since credentials are allowed.)
- 4) Wire rate limiting: add a new @Bean 'RateLimitFilter rateLimitFilter(ObjectMapper objectMapper, @Value("${app.ratelimit.auth.capacity:10}") int capacity, @Value("${app.ratelimit.auth.refill-per-minute:10}") int refill)' returning 'new RateLimitFilter(objectMapper, capacity, refill)'. Add a companion FilterRegistrationBean<RateLimitFilter> with setEnabled(false) (mirror the existing jwtAuthFilterDisabledAuto pattern) so Boot does not auto-register it on the global servlet chain. In securityFilterChain(...), inject 'RateLimitFilter rateLimitFilter' as a parameter and add '.addFilterBefore(rateLimitFilter, JwtAuthFilter.class)' (so rate limiting runs before authentication). Order vs ApiKeyAuthFilter: ApiKeyAuthFilter is added 'addFilterBefore(apiKeyFilter, JwtAuthFilter.class)'; placing rateLimit before JwtAuthFilter is fine because the rate filter self-restricts to auth paths via shouldNotFilter.
- 5) Add import 'org.springframework.beans.factory.annotation.Value' and 'com.example.cp.common.RateLimitFilter'. List import already present.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/common/RateLimitFilter.java`
- depends on: RateLimitFilter
- NEW FILE. package com.example.cp.common; extends org.springframework.web.filter.OncePerRequestFilter.
- Fields: private final ObjectMapper objectMapper; private final ConcurrentHashMap<String,io.github.bucket4j.Bucket> buckets = new ConcurrentHashMap<>(); private final int capacity; private final int refillPerMinute. Constructor stores them.
- Use bucket4j core API (classes io.github.bucket4j.Bucket, Bandwidth, Refill — transitively available via bucket4j-spring-boot-starter 0.12.8). Build per-IP bucket: Bandwidth.classic(capacity, Refill.greedy(refillPerMinute, Duration.ofMinutes(1))).
- Override shouldNotFilter(HttpServletRequest req): return true UNLESS the path+method is one of the protected ones: POST /api/v1/auth/login, POST /api/v1/auth/password-reset/request, POST /api/v1/auth/password-reset/confirm. (Match on req.getServletPath()/getRequestURI() startsWith and HttpMethod POST.) This keeps the filter limited even though it is added to the chain broadly.
- doFilterInternal: derive client key from a trusted source — use request.getRemoteAddr() (do NOT trust X-Forwarded-For unless behind the nginx proxy; document that nginx sets X-Real-IP, see nginx change). buckets.computeIfAbsent(key, k -> newBucket()). If bucket.tryConsume(1) -> chain.doFilter; else -> write 429: response.setStatus(429); response.setContentType("application/problem+json"); set Retry-After header (seconds); body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded") serialized via objectMapper. Do NOT echo the IP or any internal detail.
- Imports: jakarta.servlet.*, jakarta.servlet.http.*, io.github.bucket4j.*, org.springframework.http.{HttpStatus,ProblemDetail,MediaType}, com.fasterxml.jackson.databind.ObjectMapper, java.time.Duration, java.util.concurrent.ConcurrentHashMap, java.io.IOException.
- NOTE (multi-instance #27): document with a class Javadoc that this in-memory limiter is per-instance and a Redis-backed proxy-manager is the follow-up; acceptable for P0.

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- depends on: app.cors.allowed-origins, app.auth.expose-reset-token, app.ratelimit.auth.capacity / app.ratelimit.auth.refill-per-minute
- SCOPE NOTE: shared file — add ONLY the hardening keys below; do not restructure existing datasource/jpa/sso blocks.
- 1) Request/body/header size limits — add under a top-level 'server:' (extend existing block at server.port: 8080): add 'server.tomcat.max-http-form-post-size: 256KB', 'server.tomcat.max-swallow-size: 256KB', 'server.max-http-request-header-size: 16KB' (Spring Boot prop is server.max-http-request-header-size). Add 'spring.servlet.multipart.max-file-size: 1MB' and 'spring.servlet.multipart.max-request-size: 1MB' under the existing 'spring:' block to cap multipart bodies.
- 2) Actuator: under existing 'management:' block add a probes/health-detail tightening — add 'management.endpoint.health.show-details: never' (currently defaults expose details to authenticated; set never so even authenticated callers don't get internal component breakdown) OR 'when-authorized' if details are desired for authed ops. Recommend 'never' for P0. (The exposure include list already limits to health,info,metrics,prometheus; SecurityConfig now authenticates metrics/prometheus.)
- 3) CORS config property: add new block 'app.cors.allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173}'.
- 4) Rate-limit properties: add 'app.ratelimit.auth.capacity: ${APP_RATELIMIT_AUTH_CAPACITY:10}' and 'app.ratelimit.auth.refill-per-minute: ${APP_RATELIMIT_AUTH_REFILL:10}'.
- 5) Reset-token flag default: add 'app.auth.expose-reset-token: ${APP_AUTH_EXPOSE_RESET_TOKEN:false}' under the existing 'app.auth:' block (alongside session-secret/session-ttl). MUST remain false in this prod-default file.

### [NEW FILE] `control-panel-api/src/main/resources/application-dev.yml`
- depends on: app.auth.expose-reset-token
- NEW FILE (non-prod Spring profile 'dev'). Contains only the dev overrides that must never apply in prod: 'app.auth.expose-reset-token: true'. This is the home for the reset-token exposure (#61): the flag is true ONLY when the app runs with spring.profiles.active=dev (or test). Document at top with a comment that this file must not be activated in production.
- Optionally also set 'management.endpoint.health.show-details: always' here for local debugging.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/AuthController.java`
- depends on: app.auth.expose-reset-token
- #61 fix: replace the JVM system-property read with the Spring property. Remove 'if (Boolean.parseBoolean(System.getProperty("app.auth.expose-reset-token", "false")))' at line 159.
- Add a field 'private final boolean exposeResetToken;' and add a constructor parameter '@Value("${app.auth.expose-reset-token:false}") boolean exposeResetToken' (add import org.springframework.beans.factory.annotation.Value), assigning this.exposeResetToken = exposeResetToken. Update the constructor body and the (single) caller is Spring DI so no other call sites.
- In requestReset(...), change the guard to 'if (exposeResetToken) { response.put("reset_token", rawToken); }'. Behavior now driven by application.yml (false by default) and only flipped true under the dev profile via application-dev.yml — matching the openapi.yaml description.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/common/GlobalExceptionHandler.java`
- Stop echoing raw exception messages (#info-leak). handleAccessDenied: replace 'ex.getMessage()' detail with a constant 'Access is denied' (do not leak which resource/why). handleAuth: replace 'ex.getMessage()' with 'Authentication required' (avoid leaking provider internals).
- handleIllegal(IllegalArgumentException): currently returns ex.getMessage() as detail — this can echo arbitrary internal strings. Replace with a generic 'Bad request' detail; if a safe, caller-facing message is needed, route those through ApiException (which is intentionally caller-safe) instead. Keep MethodArgumentNotValid handler as-is (it returns field names + bean-validation messages, which are author-controlled and safe).
- handleGeneric already returns a generic message — KEEP. Add 'private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);' and log the full exception (log.error("Unhandled exception", ex)) in handleGeneric AND in handleIllegal/handleAccessDenied/handleAuth log at debug/warn so the real cause is captured server-side while the client sees only the sanitized ProblemDetail. Add imports org.slf4j.Logger, org.slf4j.LoggerFactory.
- Do NOT change handleApi (ApiException details are intentionally safe, caller-facing).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/events/OutboxPublisher.java`
- Parameterize the pg_notify SQL (#injection). The notify() method (lines 60-73) builds 'NOTIFY <channel>, '<json>'' via string concatenation with manual quote-doubling — replace with the parameterized pg_notify() function so the payload is bound, never interpolated.
- Replace the jdbc.execute(Connection->...) block with: jdbc.update("SELECT pg_notify(?, ?)", CHANNEL, json);  (pg_notify(text, text) is the SQL-callable equivalent of NOTIFY and accepts bind parameters). CHANNEL is a compile-time constant 'cp_events' so it is safe to pass as a bound arg too. Remove the now-unused java.sql.Connection lambda, the manual json.replace("'","''"), and the try-with-resources Statement.
- Remove now-unused import java.sql.* usage inside notify if no longer referenced (Connection import was inline-qualified, so just delete the lambda). Keep CHANNEL constant and ObjectMapper payload construction unchanged. The EventListener (LISTEN cp_events) is unaffected — pg_notify delivers identically.
- Note: the sibling subscriptions/OutboxPublisher.java already uses parameterized INSERT (?,?,?,?::jsonb) — no change needed there; only events/OutboxPublisher.java has the injection-prone NOTIFY.

### [MODIFY] `admin-ui/nginx.conf`
- Add security response headers inside the server block (apply to all responses, ideally via 'add_header ... always'): add_header X-Content-Type-Options "nosniff" always; add_header X-Frame-Options "DENY" always; add_header Referrer-Policy "no-referrer" always; add_header Content-Security-Policy "default-src 'self'; connect-src 'self' http://localhost:8080; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'" always; add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always (HSTS — only meaningful once TLS terminates, see below).
- TLS redirect: the container currently only listens on 80. Add a second server block that listens 80 and issues 'return 301 https://$host$request_uri;' AND change the existing app-serving server to 'listen 443 ssl;' with ssl_certificate/ssl_certificate_key pointing at mounted certs (e.g. /etc/nginx/certs/tls.crt, /etc/nginx/certs/tls.key). If TLS is terminated upstream (LB/ingress) instead, gate the redirect on the forwarded proto: in the :80 server add 'if ($http_x_forwarded_proto = "http") { return 301 https://$host$request_uri; }'. Choose ONE approach; recommend the in-nginx 443 listener for the self-contained docker-compose, with a note that certs must be mounted (compose volume) — update admin-ui Dockerfile EXPOSE to include 443 and docker-compose admin-ui service port mapping accordingly if the prod nginx image is used (note: current compose runs admin-ui as raw node dev server, not this nginx image — the nginx.conf only applies to the admin-ui/Dockerfile prod build).
- Add 'X-Real-IP'/proxy awareness note: if this nginx fronts the API, it should set proxy_set_header X-Real-IP $remote_addr; but as written it serves only the static SPA, so RateLimitFilter must key off the API's own remote addr / its fronting proxy, not this UI nginx.
- Keep existing location / try_files and /assets/ caching blocks unchanged; just nest the add_header directives at server scope so they inherit.

### [MODIFY] `control-panel-api/Dockerfile`
- Non-root USER: in the runtime stage (FROM eclipse-temurin:21-jre-alpine) add 'RUN addgroup -S app && adduser -S app -G app' before WORKDIR, ensure /app is owned by app (COPY --chown=app:app the jar, or RUN chown -R app:app /app), then add 'USER app' before ENTRYPOINT — mirror the sample-docker-app/Dockerfile pattern (lines 24, 38 there).
- HEALTHCHECK: add 'HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 CMD wget -qO- http://localhost:8080/actuator/health || exit 1' before ENTRYPOINT. (alpine jre image has wget via busybox; sample app uses the same pattern.) start-period 40s accounts for Spring Boot + Liquibase startup.
- Keep EXPOSE 8080. Optionally switch ENTRYPOINT to the exec sh -c form used by sample app only if JAVA_OPTS support is desired; not required for hardening.

### [MODIFY] `docker-compose.yml`
- Require injected secrets via ${VAR:?} for all credentials. postgres.environment: POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set} (keep POSTGRES_DB/USER or also parameterize: POSTGRES_USER: ${POSTGRES_USER:-cp}).
- control-panel-api.environment: change DB_PASS from literal 'cp' to ${DB_PASS:?DB_PASS must be set} (and align DB_USER with POSTGRES_USER). APP_KEY_ENC_MASTER already uses :? — keep.
- WIRE the session secret: add 'APP_AUTH_SESSION_SECRET: ${APP_AUTH_SESSION_SECRET:?APP_AUTH_SESSION_SECRET must be set (>=32 chars)}' to control-panel-api.environment. This is currently absent, so SessionTokenService.validate() would throw on startup (PostConstruct requires >=32 bytes). This is the missing wiring called out in the task.
- Stop publishing infra ports: REMOVE the 'ports: - 5432:5432' from the postgres service and 'ports: - 6379:6379' from redis. They communicate over the compose network by name (postgres:5432, redis:6379) already used by control-panel-api env. If local debugging access is needed, document binding to 127.0.0.1 only (e.g. '127.0.0.1:5432:5432') rather than 0.0.0.0.
- Optionally pass through new hardening envs to control-panel-api for parity: APP_CORS_ALLOWED_ORIGINS: ${APP_CORS_ALLOWED_ORIGINS:-http://localhost:5173}. Do NOT set APP_AUTH_EXPOSE_RESET_TOKEN here (leave false/prod-default); reset-token exposure belongs to the dev Spring profile only.
- Note the admin-ui service runs the node dev server (not the nginx prod image), so the nginx TLS/headers changes do not affect this compose file directly; they apply when the admin-ui/Dockerfile image is deployed.

## Tests to add

- SecurityConfig: integration test (MockMvc / @SpringBootTest) asserting an unauthenticated GET to a non-allowlisted path (e.g. /api/v1/users) returns 401/403, and that /actuator/metrics and /actuator/prometheus return 401 unauthenticated while /actuator/health and /actuator/info return 200. Assert /swagger and /.well-known/jwks.json remain public.
- CORS: test that an Origin not in app.cors.allowed-origins is rejected (no Access-Control-Allow-Origin echoed) and the configured origin is allowed; verify allowCredentials is not combined with a wildcard origin.
- RateLimitFilter: unit/integration test firing >capacity POSTs to /api/v1/auth/login from the same remote addr returns 429 with application/problem+json and a Retry-After header after the limit; a different IP is unaffected; non-auth paths (e.g. GET /actuator/health) are never rate-limited (shouldNotFilter).
- GlobalExceptionHandler: test that a thrown RuntimeException/IllegalArgumentException with a sensitive message ('jdbc:postgresql://... password=...') produces a ProblemDetail whose 'detail' is the generic constant and does NOT contain the raw message; verify the full exception is still logged.
- OutboxPublisher: test that a payload containing a single quote and a malicious string like "', (SELECT pg_sleep(5)); --" is delivered verbatim through pg_notify (parameterized) without executing/erroring — assert the EventListener (or a LISTEN test) receives the exact JSON. Use a Postgres Testcontainer.
- AuthController reset-token flag: with app.auth.expose-reset-token=false (default) the /password-reset/request response has NO reset_token field; with the dev profile (expose-reset-token=true) it includes reset_token. Confirms #61 (Spring property, not system property).
- Startup wiring: test/CI assertion that the app fails fast when APP_AUTH_SESSION_SECRET is unset or <32 bytes (SessionTokenService.validate throws IllegalStateException) — protects the new docker-compose required-secret wiring.
- Docker: smoke test (or hadolint/CI) asserting the control-panel-api image runs as non-root (USER app) and the HEALTHCHECK is present; compose config validation that 5432/6379 are not published to host.

## Risks / cross-file notes

- SecurityConfig and application.yml are shared with other auth/themes — limit edits to the listed hardening lines; flipping anyRequest() to authenticated() can break any endpoint that currently relies on the permitAll() default (audit all controllers without an explicit matcher). Verify SSO callback paths (/login/oauth2/**, /saml2/**) and JWKS are still in the allow-list before merging.
- RateLimitFilter is added via addFilterBefore(...JwtAuthFilter.class) and also registered with a disabled FilterRegistrationBean to prevent double-registration on the global servlet chain (same gotcha already handled for JwtAuthFilter). Forgetting the disabled FilterRegistrationBean will cause the filter to run twice / on all paths.
- bucket4j-spring-boot-starter is on the classpath; using its core API (io.github.bucket4j.*) directly is fine, but if the starter's own auto-config activates expecting a cache provider (Caffeine/Redis) it may fail to start or add an unwanted servlet filter. Verify bucket4j auto-config does not require bucket4j.filters config; if it errors, exclude the starter's autoconfiguration or rely on the core jar only.
- CORS List<String> binding: @Value("${app.cors.allowed-origins}") binds a comma-separated env var to List<String> in Spring Boot; ensure the YAML value and APP_CORS_ALLOWED_ORIGINS use commas (not YAML list under @Value). Mismatch yields a single-element list. allowCredentials(true) + wildcard origin is illegal in Spring Security and throws at runtime — never set '*'.
- OutboxPublisher: pg_notify payload limit is 8000 bytes; current payloads are tiny so safe, but note the cap. jdbc.update("SELECT pg_notify(?,?)") runs as a query — JdbcTemplate.update on a SELECT works but consider jdbc.queryForObject if a driver complains; behavior must remain inside the existing @Transactional publishBatch so NOTIFY fires on commit.
- nginx TLS: the admin-ui in docker-compose runs the raw node dev server, so the nginx.conf 443/HSTS changes only take effect for the admin-ui/Dockerfile prod image. Adding 'listen 443 ssl' without mounted certs will make nginx fail to start — gate behind mounted certs or use the X-Forwarded-Proto redirect variant when TLS is terminated upstream. HSTS header before real TLS is harmless but ineffective.
- docker-compose required-secret wiring: adding APP_AUTH_SESSION_SECRET / DB_PASS / POSTGRES_PASSWORD as ${VAR:?} will make 'docker compose up' fail fast if a .env is missing — update README/.env.example so CI and local dev still boot. Removing 5432/6379 host ports may break any local tooling that connected to them directly.
- application-dev.yml profile: expose-reset-token must live ONLY in the dev (non-prod) profile; confirm no prod deployment sets spring.profiles.active=dev. The default in application.yml stays false so production is safe even if the dev file is accidentally packaged.
- handleIllegal sanitization: some callers may rely on IllegalArgumentException messages reaching clients today; converting to a generic message could change API responses. Audit for any controller throwing IllegalArgumentException expecting the message to surface — migrate those to ApiException.badRequest(...) which is the sanctioned caller-safe path.
