# Design spec: session-revocation

## Shared contracts (other files depend on these — keep signatures exact)

### `SessionRevocationStore` (interface)
- **File:** `control-panel-api/src/main/java/com/example/cp/auth/SessionRevocationStore.java`
- **Purpose:** Abstraction over the jti denylist + per-user token-version so JwtAuthFilter and tests do not depend directly on Redis. Lets us ship a Redis-backed impl in prod and a no-op/in-memory impl in tests (application-test.yml points Redis at localhost:6379 with no Testcontainers Redis, so tests must not require a live Redis).
- **Signature/contract:**

```
package com.example.cp.auth; public interface SessionRevocationStore { void denylistJti(String jti, java.time.Duration ttl); boolean isJtiDenylisted(String jti); void setTokenVersion(java.util.UUID userId, long version); long currentTokenVersion(java.util.UUID userId); long bumpTokenVersion(java.util.UUID userId); }
```

### `RedisSessionRevocationStore` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/auth/RedisSessionRevocationStore.java`
- **Purpose:** Redis-backed SessionRevocationStore using the already-provisioned spring-boot-starter-data-redis. Keys: denylist 'cp:sess:denylist:jti:{jti}'="1" with PX=remaining-token-life; token-version 'cp:sess:tokenver:{userId}' as a Long with no TTL. Active only when a RedisConnectionFactory bean exists (prod). bumpTokenVersion uses INCR (atomic).
- **Signature/contract:**

```
package com.example.cp.auth; @Component @ConditionalOnBean(org.springframework.data.redis.connection.RedisConnectionFactory.class) public class RedisSessionRevocationStore implements SessionRevocationStore { public RedisSessionRevocationStore(StringRedisTemplate redis) {...} }
```

### `InMemorySessionRevocationStore` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/auth/InMemorySessionRevocationStore.java`
- **Purpose:** Fallback SessionRevocationStore (ConcurrentHashMap denylist with expiry timestamps + AtomicLong-per-user token versions). Registered as @ConditionalOnMissingBean(SessionRevocationStore.class) so it activates only when Redis is absent (tests, local dev without Redis). Guarantees the auth path never NPEs and tests run without Redis.
- **Signature/contract:**

```
package com.example.cp.auth; public class InMemorySessionRevocationStore implements SessionRevocationStore { ... }
```

### `stringRedisTemplate` (bean)
- **File:** `control-panel-api/src/main/java/com/example/cp/auth/SessionRevocationConfig.java`
- **Purpose:** StringRedisTemplate bean over the auto-configured RedisConnectionFactory, used by RedisSessionRevocationStore. Spring Boot RedisAutoConfiguration already provides StringRedisTemplate when a connection factory exists, so a custom @Bean is only needed if explicit configuration is desired; otherwise rely on auto-config.
- **Signature/contract:**

```
org.springframework.data.redis.core.StringRedisTemplate (auto-configured)
```

### `SessionTokenService.ParsedToken (modified)` (record)
- **File:** `control-panel-api/src/main/java/com/example/cp/auth/SessionTokenService.java`
- **Purpose:** Carry the jti and tokenVersion out of the parsed JWT so JwtAuthFilter can check the denylist and compare versions. Currently ParsedToken has no jti/tokenVersion and parse() silently discards the jwtID.
- **Signature/contract:**

```
public record ParsedToken(UUID userId, String email, boolean superAdmin, Set<String> authorities, Instant expiresAt, String jti, long tokenVersion) {}
```

### `app.auth.session-ttl` (config-property)
- **File:** `control-panel-api/src/main/resources/application.yml`
- **Purpose:** Shorten default session TTL from PT12H to PT30M (P0 quick win: smaller blast radius for stolen/orphaned tokens). Set in application.yml and application-test.yml and the SessionTokenService @Value default.
- **Signature/contract:**

```
app.auth.session-ttl: PT30M (Duration; default in @Value("${app.auth.session-ttl:PT30M}"))
```

### `app.auth.revocation.enabled` (config-property)
- **File:** `control-panel-api/src/main/resources/application.yml`
- **Purpose:** Feature flag (default true) to allow disabling per-request denylist/version checks in environments without Redis if desired. Read by JwtAuthFilter via constructor-injected @Value.
- **Signature/contract:**

```
app.auth.revocation.enabled: true (boolean)
```

### `13-user-token-version.sql` (db-migration)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-user-token-version.sql`
- **Purpose:** Add users.token_version BIGINT NOT NULL DEFAULT 0 as the authoritative source of the per-user token version (Redis is a cache/fast-path; DB is durable). JwtAuthFilter compares the token's tv claim against the reloaded User.tokenVersion, so a Redis flush cannot silently un-revoke sessions.
- **Signature/contract:**

```
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
```

## File edits

### [NEW FILE] `control-panel-api/src/main/resources/db/changelog/changes/13-user-token-version.sql`
- depends on: 13-user-token-version.sql
- Create new Liquibase formatted-SQL changelog matching the existing convention (see 99-auth-password-reset.sql).
- First line: `--liquibase formatted sql`.
- Add `--changeset cp:13-users-token-version` then `ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;`
- Add `--rollback ALTER TABLE users DROP COLUMN token_version;`
- Rationale: DB is the durable source of truth for token-version; Redis token-version key is a write-through cache. On suspend/delete/password-reset we bump this column AND the Redis key.

### [MODIFY] `control-panel-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Append a new include entry AFTER the `12-additional-permissions.sql` include and BEFORE or AFTER `99-auth-password-reset.sql` (order vs 99 does not matter; both only touch existing tables). Add:
  - include:
      file: db/changelog/changes/13-user-token-version.sql
- Place it as the last numbered include (after line 27) to keep numeric ordering; the 99- file remains last.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/users/User.java`
- Add a new persistent field after `superAdmin` (line 47) / before `createdAt`:
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;
- Use @Builder.Default so the existing User.builder() calls in UserService.createUser, SsoSuccessHandler.jitCreateUser, and any test builders default to 0 instead of Lombok's uninitialized 0 warning (long defaults to 0 anyway, but @Builder.Default makes intent explicit and avoids Lombok warning).
- Lombok @Getter/@Setter already generate getTokenVersion()/setTokenVersion(long); no manual accessors needed.
- NOTE: ddl-auto is `validate`, so the column MUST exist via migration 13 before the entity is loaded — these two changes ship together.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/SessionTokenService.java`
- depends on: SessionTokenService.ParsedToken (modified), app.auth.session-ttl
- Change the `issue` signature to accept the user's current token version:
    public IssuedToken issue(UUID userId, String email, boolean superAdmin, Collection<String> authorities, long tokenVersion)
- In the claims builder (around line 60-69) add a claim: `.claim("tv", tokenVersion)` (long). Keep the existing `.jwtID(UUID.randomUUID().toString())` — that jti is what the denylist keys on.
- In `parse` (line 81-110): after extracting subject/email/super_admin/authorities, also extract:
    String jti = claims.getJWTID();
    Long tvObj = claims.getLongClaim("tv"); long tv = tvObj == null ? 0L : tvObj;
  (use getLongClaim; Nimbus returns Long).
- Change the returned ParsedToken to include jti and tv:
    return new ParsedToken(userId, email, superAdmin, authorities, exp == null ? null : exp.toInstant(), jti, tv);
- Update the record definition (line 118) to:
    public record ParsedToken(UUID userId, String email, boolean superAdmin, Set<String> authorities, Instant expiresAt, String jti, long tokenVersion) {}
- Change the TTL @Value default from PT12H to PT30M:
    @Value("${app.auth.session-ttl:PT30M}") Duration ttl  AND the fallback `this.ttl = ttl == null ? Duration.ofMinutes(30) : ttl;` (line 40).
- Keep the existing `parse()` expiry check; the denylist/version checks are layered on top in the filter, not here, so SessionTokenService stays free of Redis/DB dependencies.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/auth/SessionRevocationStore.java`
- depends on: SessionRevocationStore
- New interface in package com.example.cp.auth with exactly the signature in sharedContracts: denylistJti(String jti, Duration ttl), boolean isJtiDenylisted(String jti), void setTokenVersion(UUID, long), long currentTokenVersion(UUID), long bumpTokenVersion(UUID).
- Document: denylistJti ttl MUST equal the remaining token life (exp - now) so the key self-expires; never store with infinite TTL. currentTokenVersion returns the cached version or a sentinel (e.g. -1) when not present so callers can fall back to the DB value.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/auth/RedisSessionRevocationStore.java`
- depends on: SessionRevocationStore, stringRedisTemplate
- New @Component implementing SessionRevocationStore, annotated @ConditionalOnBean(RedisConnectionFactory.class) so it is only created when Redis auto-config produced a connection factory (prod path).
- Constructor injects StringRedisTemplate (auto-configured by Spring Boot RedisAutoConfiguration when a connection factory exists).
- Key helpers: denylistKey(jti)="cp:sess:denylist:jti:"+jti ; tokenVerKey(userId)="cp:sess:tokenver:"+userId.
- denylistJti: `redis.opsForValue().set(denylistKey(jti), "1", ttl)` — guard ttl<=0 (do not write if already expired).
- isJtiDenylisted: `return Boolean.TRUE.equals(redis.hasKey(denylistKey(jti)))`.
- setTokenVersion: `redis.opsForValue().set(tokenVerKey(userId), Long.toString(version))`.
- currentTokenVersion: read string; return -1 if null (cache miss) else Long.parseLong.
- bumpTokenVersion: `return redis.opsForValue().increment(tokenVerKey(userId))` (atomic INCR; creates key at 1 if absent — see risk note about seeding).
- Wrap Redis ops in try/catch logging at WARN and FAILING CLOSED for isJtiDenylisted (treat Redis outage as 'cannot confirm not-revoked'); but for currentTokenVersion return -1 on error so the filter falls back to DB compare. Document this fail-mode decision in a class Javadoc.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/auth/InMemorySessionRevocationStore.java`
- depends on: SessionRevocationStore
- New class implementing SessionRevocationStore, registered as a @Bean only when no other SessionRevocationStore exists (see SessionRevocationConfig). Do NOT put @Component here, to avoid two beans when Redis impl is active.
- Fields: ConcurrentHashMap<String, Instant> denylist (jti -> expiry instant); ConcurrentHashMap<UUID, AtomicLong> versions.
- denylistJti: store expiry = now+ttl (skip if ttl<=0).
- isJtiDenylisted: present AND expiry after now (lazily evict on read if expired).
- setTokenVersion: versions.put(userId, new AtomicLong(version)).
- currentTokenVersion: return -1 if absent else value.
- bumpTokenVersion: versions.computeIfAbsent(userId, k->new AtomicLong(0)).incrementAndGet().
- This keeps the test profile (no Testcontainers Redis) green and gives local dev a working fallback.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/auth/SessionRevocationConfig.java`
- depends on: SessionRevocationStore, InMemorySessionRevocationStore
- New @Configuration providing the fallback bean:
    @Bean @ConditionalOnMissingBean(SessionRevocationStore.class) public SessionRevocationStore inMemorySessionRevocationStore() { return new InMemorySessionRevocationStore(); }
- This guarantees exactly one SessionRevocationStore bean: Redis impl when a RedisConnectionFactory exists, in-memory otherwise. Avoids NoSuchBeanDefinitionException in tests and ambiguity in prod.
- Optional: if you prefer not to rely on Boot's auto StringRedisTemplate, declare `@Bean @ConditionalOnBean(RedisConnectionFactory.class) StringRedisTemplate stringRedisTemplate(RedisConnectionFactory f){ return new StringRedisTemplate(f); }` here. Default Boot auto-config already supplies it, so this is optional.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/JwtAuthFilter.java`
- depends on: SessionRevocationStore, SessionTokenService.ParsedToken (modified), app.auth.revocation.enabled
- Add constructor dependencies: SessionRevocationStore revocationStore, UserRepository userRepository, and boolean revocationEnabled (from @Value injected in SecurityConfig and passed in). Update the constructor (line 33-39) and fields accordingly. IMPORTANT: JwtAuthFilter is instantiated manually in SecurityConfig.jwtAuthFilter() (NOT component-scanned), so the new deps must be added to that @Bean method too.
- After `parsed = tokenService.parse(token)` (line 54) and BEFORE building authorities, insert the revocation/re-check block (only when revocationEnabled):
- (a) Denylist check: `if (revocationStore.isJtiDenylisted(parsed.jti())) { writeProblem(response, 401, "Unauthorized", "Session has been revoked"); return; }` — guard parsed.jti()!=null.
- (b) Reload user: `User user = userRepository.findById(parsed.userId()).orElse(null); if (user == null) { writeProblem(response,401,"Unauthorized","User not found"); return; }`.
- (c) Status check: `if (user.getStatus() != User.Status.ACTIVE) { writeProblem(response,401,"Unauthorized","Account is not active"); return; }`.
- (d) Token-version check: compute current version = max(DB user.getTokenVersion(), revocationStore.currentTokenVersion(userId) when >=0). If `parsed.tokenVersion() < effectiveCurrentVersion` -> revoked: `writeProblem(response,401,"Unauthorized","Session has been revoked"); return;`. (Use DB as source of truth; Redis only accelerates and may be -1 on miss.)
- (e) Prefer current server state over frozen claims for super_admin and authorities: replace the existing authority-resolution block (lines 60-68) so that `boolean superAdmin = user.isSuperAdmin();` (from reloaded User, NOT parsed.superAdmin()), and ALWAYS resolve authorities fresh from authoritiesLoader.authoritiesFor(parsed.userId(), null, superAdmin) instead of trusting parsed.authorities(). This closes the gap where a non-super-admin's frozen authorities claim is used until token expiry. (Note: per-request DB hit for authorities; acceptable for a control panel and consistent with the existing super-admin branch.)
- Build AuthenticatedUser with the reloaded superAdmin and freshly-resolved authorityCodes (line 72-73) — drop use of parsed.superAdmin() there.
- When revocationEnabled is false, keep a degraded path: still reload user for status check is recommended even with flag off, but minimally retain current behavior; document that disabling the flag also disables denylist + version checks (status/authorities re-check should remain on regardless — make status+authorities refresh unconditional, gate only the Redis denylist+version checks behind the flag to limit blast radius if Redis is down).
- writeProblem already exists (line 89) and matches GlobalExceptionHandler's ProblemDetail shape — reuse it; do not introduce a new error format.
- Keep AuditContext.setActor/setIp and the finally{AuditContext.clear()} block unchanged (lines 79-86).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java`
- depends on: SessionRevocationStore
- Add fields/constructor params for `SessionRevocationStore sessionRevocationStore`, `UserRepository userRepository`, and `@Value("${app.auth.revocation.enabled:true}") boolean revocationEnabled` (constructor injection on this @Configuration). Update constructor (line 32-38) and fields (line 28-30).
- Update the jwtAuthFilter() @Bean (line 40-43) to pass the new deps:
    return new JwtAuthFilter(sessionTokenService, authoritiesLoader, objectMapper, sessionRevocationStore, userRepository, revocationEnabled);
- No change to the filter-chain wiring, CORS, or permitAll matchers. /api/v1/auth/** stays permitAll so /logout still reaches the controller (logout reads SecurityContext set by the filter when a valid Bearer token is present).
- Import com.example.cp.users.UserRepository and com.example.cp.auth.SessionRevocationStore.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/AuthController.java`
- depends on: SessionRevocationStore, SessionTokenService.ParsedToken (modified), 13-user-token-version.sql
- Inject SessionRevocationStore into the constructor (add field + param). Update constructor (line 61-79) and field list (line 51-59).
- login(): change the tokenService.issue call (line 106-107) to pass the user's token version:
    tokenService.issue(user.getId(), user.getEmail(), user.isSuperAdmin(), authorities, user.getTokenVersion())
- logout(): replace the no-op body (line 118-126). Read the current AuthenticatedUser; to denylist the exact jti, re-parse the bearer token. Add `HttpServletRequest request` param to logout(), extract the Authorization header (Bearer), call tokenService.parse(token) to get ParsedToken, then `revocationStore.denylistJti(parsed.jti(), Duration.between(Instant.now(), parsed.expiresAt()))` (guard expiresAt!=null and positive). Keep the existing AuditContext.set("auth.logout")/setTarget. Still return 204. If header/token missing, just audit+204 (idempotent).
- Add imports: jakarta.servlet.http.HttpServletRequest, java.time.Duration (Instant already imported).
- confirmReset(): no change needed here for revocation BEYOND what UserService.setPassword now does (UserService bumps version + denylists) — but ADD nothing that double-bumps. Leave confirmReset as-is; the bump happens inside userService.setPassword.
- Do NOT denylist in confirmReset directly; centralize all version bumps in UserService so AuthController and admin endpoints share one path.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/users/UserService.java`
- depends on: SessionRevocationStore, 13-user-token-version.sql
- Inject SessionRevocationStore into the constructor (add field + param). Update constructor (line 19-22).
- Add a private helper:
    private void revokeAllSessions(User u) { long v = u.getTokenVersion() + 1; u.setTokenVersion(v); /* userRepository.save(u) happens in caller */ try { revocationStore.setTokenVersion(u.getId(), v); } catch(Exception e){ /* log warn; DB is source of truth */ } }
  Keep DB and Redis write-through consistent; DB bump is authoritative, Redis is best-effort.
- changePassword (line 71-84): after setting the new hash and before/with userRepository.save(u), call revokeAllSessions(u) so all existing sessions are invalidated when a user changes their password.
- setPassword (line 86-96): same — call revokeAllSessions(u) before userRepository.save(u). This covers the AuthController password-reset confirm flow.
- deactivate (line 98-105): this sets status=SUSPENDED. Add revokeAllSessions(u) before save so suspended users are kicked out immediately (status re-check in the filter also catches this, but bumping version is belt-and-suspenders and covers the window where status check might be flag-gated).
- Add a new method `@Transactional public void delete(UUID id)`: load user, set status = User.Status.DELETED, call revokeAllSessions(u), AuditContext.set("user.deleted")/setTarget, save. (No hard delete; matches the DELETED enum + CHECK constraint in 01-organizations-users.sql.) This is the 'delete bumps token-version / denylists all sessions' requirement.
- Because the per-user version bump invalidates ALL of a user's tokens at once (no need to enumerate jtis), we do NOT need a Redis set of all active jtis — the token-version mechanism is the bulk-revocation primitive; the jti denylist is only for single-session logout.
- Imports: add com.example.cp.auth.SessionRevocationStore.
- NOTE on cross-package dependency: UserService (package users) will depend on SessionRevocationStore (package auth). auth already depends on users (JwtAuthFilter, AuthController import users.*), so adding users->auth creates a bidirectional package dependency. This compiles fine in one Maven module (no cyclic compile error at the class level as long as there's no constructor cycle), but to keep it clean consider placing SessionRevocationStore in com.example.cp.common instead of com.example.cp.auth. RECOMMENDATION: put SessionRevocationStore + impls + config in com.example.cp.common (or a new com.example.cp.session package) to avoid the users<->auth package coupling. Adjust all package/import lines above accordingly if you take this option.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/users/UserController.java`
- Add a delete endpoint to expose UserService.delete and ensure admin-initiated delete revokes sessions:
    @PostMapping("/{id}/delete") @PreAuthorize("hasAuthority('user.write')") public ResponseEntity<Void> delete(@PathVariable UUID id){ userService.delete(id); return ResponseEntity.noContent().build(); }
- No change to existing deactivate endpoint (line 43-48) — UserService.deactivate now also revokes sessions.
- Verify 'user.write' authority exists (granted via RBAC seed / 12-additional-permissions.sql) before relying on it; reuse the same authority already used by deactivate to avoid introducing a new permission code.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java`
- jitCreateUser (line 60-70): no functional change required for token-version (User.tokenVersion defaults to 0 via @Builder.Default). Confirm the builder still compiles after adding the field.
- Note for reviewers: SsoSuccessHandler currently does NOT issue a session JWT (it only redirects to the UI). So it needs no issue()-signature update. If a follow-up adds SSO->JWT minting, it must pass user.getTokenVersion() like AuthController.login does.

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- Change `session-ttl: PT12H` (line 37) to `session-ttl: PT30M`.
- Under `app.auth` add:
    revocation:
      enabled: ${APP_AUTH_REVOCATION_ENABLED:true}
- Redis url is already configured (lines 17-19): `spring.data.redis.url: ${REDIS_URL:redis://localhost:6379}` — no change. RedisAutoConfiguration will create the connection factory + StringRedisTemplate from this.

### [MODIFY] `control-panel-api/src/main/resources/application-test.yml`
- Change `session-ttl: PT12H` (line 22) to `session-ttl: PT30M` to mirror prod (or keep short; value only affects issued-token exp in tests).
- Add under app.auth: `revocation.enabled: true` so tests exercise the denylist/version path against the InMemorySessionRevocationStore.
- CRITICAL: tests must NOT require a live Redis. There is no Testcontainers Redis here. Two safe options: (1) remove/neutralize the `spring.data.redis` block in application-test.yml AND ensure RedisAutoConfiguration does not eagerly connect (Lettuce connects lazily, so RedisConnectionFactory bean exists but RedisSessionRevocationStore would be selected and fail on first op). Therefore (2) PREFERRED: in the test profile exclude Redis auto-config so no RedisConnectionFactory bean exists and InMemorySessionRevocationStore is selected — add `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration` to application-test.yml and remove the `spring.data.redis` block. This makes @ConditionalOnBean(RedisConnectionFactory.class) false in tests -> in-memory fallback wins.

## Tests to add

- JwtAuthFilter rejects a token whose jti was denylisted (logout): obtain token via /login, call /logout, then call /api/v1/auth/me with the same token -> 401 ProblemDetail title 'Unauthorized' detail 'Session has been revoked'.
- JwtAuthFilter rejects a token with stale token-version: issue token at version N, bump user token-version (via deactivate/delete/password change), reuse old token -> 401 'Session has been revoked'.
- JwtAuthFilter rejects when reloaded user status != ACTIVE: suspend user via /api/v1/users/{id}/deactivate then call a protected endpoint with a token minted before suspension -> 401 'Account is not active'.
- JwtAuthFilter prefers current server super_admin state: mint a token while user.superAdmin=false, then flip user.superAdmin=true in DB (or vice-versa) -> authorities reflect current DB state, not the frozen claim (verify a super-admin-only endpoint becomes accessible/denied accordingly).
- JwtAuthFilter resolves authorities fresh: grant a new permission to a user after their token was issued -> /api/v1/auth/me permissions reflect the new permission without re-login.
- UserService.delete sets status=DELETED and bumps token_version + Redis setTokenVersion; existing sessions are revoked.
- UserService.changePassword and setPassword (password reset confirm) bump token_version so all prior sessions are invalidated; verify reset-confirm then old token -> 401.
- Logout is idempotent and safe with no/invalid Authorization header -> 204, no exception.
- denylist TTL self-expiry: denylistJti with a small ttl; isJtiDenylisted true immediately, false after expiry (InMemory impl unit test).
- Token still has jti and tv claims after issue() change: unit test SessionTokenService.issue then parse round-trips jti (non-null) and tokenVersion.
- Redis-down resilience: with revocation.enabled=true and InMemory fallback, the auth path still performs status + fresh-authorities re-check (these are unconditional) even though Redis denylist/version are gated.
- Default TTL is 30 minutes: unit-assert SessionTokenService.ttl() == Duration.ofMinutes(30) under default config.

## Risks / cross-file notes

- ddl-auto=validate: User.tokenVersion field and migration 13 MUST ship together, in the same change, or Hibernate schema validation fails at startup. Add the column via Liquibase (13-...sql) AND register it in db.changelog-master.yaml.
- JwtAuthFilter is hand-instantiated in SecurityConfig.jwtAuthFilter() (not @Component). Any new constructor params (SessionRevocationStore, UserRepository, revocationEnabled flag) MUST be threaded through that @Bean method or it won't compile / will use stale signature.
- ParsedToken is a record; adding fields (jti, tokenVersion) breaks every `new ParsedToken(...)` and every consumer pattern-matching/destructuring it. Only SessionTokenService.parse constructs it and only JwtAuthFilter reads it today, but grep for ParsedToken usages before committing.
- SessionTokenService.issue signature change (added long tokenVersion) breaks ALL callers: AuthController.login is the only current caller; confirm no other module (SSO, tests) calls issue(). Update them in the same change.
- Cross-package coupling: putting SessionRevocationStore in com.example.cp.auth makes com.example.cp.users depend on auth while auth already depends on users (bidirectional). It compiles in a single module but is a smell; prefer com.example.cp.common (or a new session package) to avoid it. If kept in auth, ensure no constructor bean cycle: UserService(->store) and JwtAuthFilter(->UserRepository) do not form a cycle because the store has no dependency on UserService.
- Bean selection correctness: exactly one SessionRevocationStore must exist. RedisSessionRevocationStore uses @ConditionalOnBean(RedisConnectionFactory.class); InMemory is @ConditionalOnMissingBean(SessionRevocationStore.class). In tests, RedisAutoConfiguration creates a RedisConnectionFactory by default (Lettuce, lazy), which would select the Redis impl and then fail on the first Redis op since no server runs. MUST exclude RedisAutoConfiguration in application-test.yml so the in-memory bean is chosen.
- Fail-open vs fail-closed: decide and document. Recommended: status + fresh-authorities re-check are UNCONDITIONAL (always reload user); only the Redis jti-denylist and Redis-version fast-path are gated by app.auth.revocation.enabled and by Redis availability. DB token_version compare remains the durable bulk-revocation check and should run whenever revocation is enabled.
- Performance: filter now does a UserRepository.findById + AuthoritiesLoader DB query per authenticated request (previously authorities were taken from claims for non-super-admins). For a control panel this is acceptable, but note the added DB load; consider a short-lived cache later. This is a deliberate trade for correctness (per-request re-check #8).
- Redis INCR seeding: bumpTokenVersion via Redis INCR on a missing key starts at 1, but the DB column starts at 0 and is the source of truth. Always derive the new version from DB (u.getTokenVersion()+1) and write-through to Redis with setTokenVersion(newVersion); do NOT rely on Redis INCR alone, or Redis and DB versions can diverge after a Redis flush.
- logout() must re-parse the bearer token to get the jti; if the token is expired/invalid, tokenService.parse throws ApiException — catch it and still return 204 (idempotent logout) rather than 401.
- CITEXT email + per-request user reload: ensure findById(parsed.userId()) (UUID) is used, not email lookup, to avoid case/locale issues; userId from the JWT subject is stable.
- Shortening TTL to 30m changes client behavior (more frequent re-login). Coordinate with admin-ui; there is currently no refresh-token mechanism, so 30m may be aggressive — confirm with product or expose via APP_AUTH_SESSION_TTL env so it can be tuned without redeploy (already overridable via @Value).
