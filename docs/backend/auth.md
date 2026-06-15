# `com.example.cp.auth` — Authentication, Sessions & Login Hardening

## Module overview

This package is the **front door** of the control-panel API. It owns everything between "an
anonymous HTTP request arrives" and "a `SecurityContext` holds an authenticated principal": the
login/MFA-login/logout REST endpoints, the stateless HS256 **session JWT** (issued and parsed by
`SessionTokenService`, also carried as the `cp_session` HttpOnly cookie), the Spring Security filter
chains and `JwtAuthFilter` that authenticate every protected request, two **session-revocation
stores** (a per-session `jti` denylist + a per-user token-version fast-path, Redis-backed in prod /
in-memory otherwise), a cluster-safe brute-force **login rate limiter** (again Redis-or-memory),
the bcrypt **password encoder** and **password strength policy**, and the **password-reset token**
entity/repository. It deliberately keeps two distinct rate-limiting mechanisms (a token-bucket
per-IP request limiter and a sliding-window per-account/per-IP lockout) and two distinct revocation
mechanisms (single-session `jti` denylist vs. bulk token-version bump).

A central design choice runs through the whole package: **the session JWT is stateless, but trust in
it is not.** Every protected request re-loads the `User` from the database and re-resolves
authorities and super-admin status fresh, so a suspended user, a revoked session, or a stale
privilege claim is rejected before token expiry — the JWT's own claims are treated as a hint, not as
authority. This was a direct outcome of the security audit/remediation (several methods cite finding
numbers such as #32 token-theft-via-localStorage and P2 the Redis-registration trap).

### How it fits the bigger picture

The control panel issues **offline-verifiable Ed25519 `.lic` licenses** to customer Docker apps; that
licensing path is entirely separate from *this* package. `com.example.cp.auth` secures the
**control-panel's own admin/API surface** — the React `admin-ui` SPA and any Bearer/API clients that
manage orgs, users, subscriptions and licenses. It collaborates with:

- `com.example.cp.rbac.AuthoritiesLoader` — turns a user (+ super-admin flag) into the permission
  code set and Spring `GrantedAuthority` objects used for `@PreAuthorize` method security.
- `com.example.cp.users` (`User`, `UserService`, `UserRepository`) — the durable identity store;
  `UserService.revokeAllSessions` is the *write* side of the token-version revocation this package
  *reads*.
- `com.example.cp.mfa.MfaService` — issues/verifies the TOTP second factor and the signed MFA
  challenge that glues the two-step login together.
- `com.example.cp.sso.SsoSuccessHandler` — the OAuth2/SAML browser-login success path that mints the
  same `cp_session` cookie this package's filter consumes.
- `com.example.cp.apikeys.ApiKeyAuthFilter` — an alternate authentication filter for customer API
  keys, slotted into the same chain.
- `com.example.cp.common` — `ApiException` (RFC-7807 errors), `AuditContext`/`AuditWriter` (audit
  trail), `SecurityUtils` (current-principal access), `TrustedProxyResolver` (non-spoofable client
  IP), and `RateLimitFilter` (the per-IP token bucket).

---

## The big picture: request & login control flow

Two filter chains exist (see `SecurityConfig`). Almost all traffic hits the stateless API chain:

```
                  ┌─────────────────── Order(1) ssoFilterChain ───────────────────┐
  /oauth2/** ────►│ permitAll; installs oauth2Login/saml2Login redirect entrypoints│
  /saml2/**  ────►│ → IdP redirect, on success SsoSuccessHandler mints cp_session  │
                  └────────────────────────────────────────────────────────────────┘

                  ┌─────────────────── Order(2) securityFilterChain (STATELESS) ───┐
  everything  ───►│ RateLimitFilter (per-IP token bucket; auth POSTs only)         │
  else            │   → ApiKeyAuthFilter (if present)                              │
                  │     → JwtAuthFilter (Bearer header OR cp_session cookie)       │
                  │        → authorizeHttpRequests rules                           │
                  │           → controller (@PreAuthorize method security)         │
                  └────────────────────────────────────────────────────────────────┘
```

**Two-step login** (the heart of `AuthController`):

```
POST /api/v1/auth/login {email, password}
  │  RateLimitFilter: per-IP token bucket (429 if drained)
  │  isLocked(email, ip)?  ── yes ─► 401 "Too many failed attempts" (audit auth.login.locked, DENIED)
  │  lookup user / status / bcrypt password
  │      └─ any failure ─► recordFailure(email, ip); 401 "Invalid email or password"
  │  recordSuccess(email, ip)
  ├─ mfaService.isEnabled(user)?
  │     YES ─► issueChallenge → 200 { mfaRequired:true, mfaChallenge:<JWT>, mfaChallengeExpiresAt }
  │     NO  ─► completeSession → 200 { accessToken, expiresAt, user } + Set-Cookie cp_session
  ▼
POST /api/v1/auth/mfa/login {challenge, code}   (only when MFA enabled)
  │  parseChallenge(challenge)  → userId   (proves step-1 password succeeded)
  │  isLocked(email, ip)?  ── yes ─► 401 (audit auth.login.mfa.locked)
  │  mfaService.verifyLoginCode(userId, code)?
  │      └─ no ─► recordFailure; 401 "Invalid code"
  │  recordSuccess → completeSession → 200 session + cp_session cookie
```

The signed `challenge` is the *only* bridge between the two calls — there is no email-only path — so
MFA can never collapse into a single TOTP guess (see `AuthController.mfaLogin` notes below).

---

## File-by-file reference

### `AuthController.java`
**`@RestController` at `/api/v1/auth`** — the public authentication surface. All endpoints here are
reachable without a session (they are listed under `permitAll()` in `SecurityConfig`), because they
*establish* a session. It orchestrates login, the two-step MFA login, logout (session revocation),
password reset request/confirm, and the authenticated `/me` profile lookup.

**Constructor dependencies** (13 collaborators + 1 flag): `UserRepository`, `UserService`,
`PasswordEncoder`, `SessionTokenService`, `AuthoritiesLoader`, `PasswordResetTokenRepository`,
`LoginRateLimiter`, `OrgMemberRepository`, `OrganizationRepository`, `SessionRevocationStore`,
`MfaService`, `AuditWriter`, `TrustedProxyResolver`, and the `@Value app.auth.expose-reset-token`
boolean. The field `cookieSecure` (`@Value app.auth.cookie-secure`, default `true`) is field-injected.

| Config property | Default | Purpose |
|---|---|---|
| `app.auth.expose-reset-token` | `false` | When true (dev/test), the raw reset token is returned in the JSON response instead of being emailed. |
| `app.auth.cookie-secure` | `true` | Whether `cp_session` carries the `Secure` attribute (HTTPS only). Turn off only for local HTTP dev. |

#### Methods

**`login(LoginRequest, HttpServletRequest, HttpServletResponse)` — `POST /login`, `@Transactional`**
The step-1 entry point. Flow and the *why* behind each guard:
1. Trims `email`; resolves the **real client IP** via `proxyResolver.resolveClientIp(request)` (not
   raw `getRemoteAddr()`, so the per-IP lockout is meaningful behind the TLS-terminating proxy).
2. `loginRateLimiter.isLocked(email, ip)` is checked **first**, before any DB/bcrypt work, so a
   locked account/IP cannot be used to spin bcrypt. On lock it writes an explicit `auth.login.locked`
   **DENIED** audit row and calls `AuditContext.markRecorded()` so the audit aspect does not emit a
   duplicate, then throws 401.
3. Unknown user, inactive status (`User.Status != ACTIVE`), and bad password (`passwordEncoder.matches`)
   all funnel through `recordLoginFailed(...)` + `loginRateLimiter.recordFailure(email, ip)` and
   throw the **same** generic `401 "Invalid email or password"` for the unknown-user/bad-password
   cases (no user-enumeration leak). Note the null-hash guard `user.getPasswordHash() == null` — an
   SSO-only user with no local password cannot be password-logged-in.
4. On success: `recordSuccess(email, ip)` clears the account counter, then **branches on MFA**. If
   `mfaService.isEnabled`, it returns a `LoginResponse.mfaChallenge(...)` (NO session token issued);
   otherwise it delegates to `completeSession`.
5. Sets `AuditContext` action/target so the audit aspect records `auth.login` or
   `auth.login.mfa_challenge`.

*Edge case / gotcha:* the rate limiter counts failures by **email + IP**, and `recordSuccess` only
clears the *account* counter, not the IP counter — a noisy IP stays throttled even after one success.

**`mfaLogin(MfaLoginRequest, …)` — `POST /mfa/login`, `@Transactional`** — step 2.
- A non-blank signed `challenge` is **mandatory** (`400` if missing). This is the only proof step-1
  passed, keeping MFA a true second factor. `mfaService.parseChallenge` verifies the challenge's
  HS256 signature, `purpose=mfa_challenge`, issuer and expiry, returning the bound `userId`.
- The lockout (`isLocked(email, ip)`) is consulted **before** verifying the code, and every bad code
  calls `recordFailure`, so the 6-digit TOTP cannot be brute-forced even while the attacker still
  holds a valid challenge. A locked attempt writes an `auth.login.mfa.locked` DENIED audit row.
- Re-checks `Status == ACTIVE`, then `mfaService.verifyLoginCode(userId, code)` (which is replay-safe
  — it advances `last_accepted_step`). Success → `recordSuccess` → `completeSession`.

**`completeSession(User, HttpServletResponse)` — private.** The single place a real session is
minted (shared by non-MFA login and MFA step-2):
1. Resolves **fresh** authorities: `authoritiesLoader.authoritiesFor(user.getId(), null, user.isSuperAdmin())`.
2. `tokenService.issue(userId, email, superAdmin, authorities, user.getTokenVersion())` → an
   `IssuedToken(token, expiresAt)`. The `tokenVersion` is baked into the JWT so a later bulk-revoke
   can invalidate it.
3. Stamps `user.setLastLoginAt(now)` and saves.
4. Builds the **`cp_session` cookie**: `HttpOnly`, `Secure`(=`cookieSecure`), `SameSite=Lax`,
   `Path=/`, `Max-Age=tokenService.ttl()`. *Why:* browser clients never have to keep the JWT in
   JS-readable `localStorage` (mitigates XSS token theft, finding #32). The `accessToken` is *also*
   returned in the body for non-browser Bearer clients.

**`logout(HttpServletRequest, HttpServletResponse)` — `POST /logout`.** Revokes the *current*
session of a stateless JWT:
- Resolves the token with the **same precedence as `JwtAuthFilter`**: Bearer header first, else the
  `cp_session` cookie (a post-SSO browser only holds the cookie — without this, SSO sessions could
  never be revoked).
- Parses the token; if it has a `jti` and a future `exp`, computes the remaining TTL and
  `revocationStore.denylistJti(jti, ttl)` so the exact token is denied for its remaining life.
- **Failure handling matters here:** an expired/invalid token is swallowed (`logout` is idempotent —
  nothing to deny). But a `RevocationStoreException` (e.g. Redis outage) is *not* swallowed — it sets
  the audit outcome to FAILED and returns **`503 Service Unavailable`** so the client retries, rather
  than falsely reporting a logout while the token stays valid.
- **Always** clears the cookie (`Max-Age=0`, same attributes as login) regardless, so the browser
  drops it even when the jti was already denylisted/expired. Returns `204`.

**`requestReset(PasswordResetRequest)` — `POST /password-reset/request`, `@Transactional`.**
- Returns `{"status":"ok"}` **unconditionally** for a missing/inactive user — no existence leak.
- For an active user: generates a 32-byte URL-safe random raw token (`SecureRandom`), stores only its
  **SHA-256 hash** (`PasswordResetToken`, 60-minute TTL) — the raw token is never persisted.
- Only when `exposeResetToken` is true does the raw token appear in the response (`reset_token`); in
  prod it would be emailed.

**`confirmReset(PasswordResetConfirm)` — `POST /password-reset/confirm`, `@Transactional`.**
- Looks up by SHA-256 hash; rejects missing/blank token, already-used token (`usedAt != null`), and
  expired token — all with deliberately generic `400 "Invalid or expired token"`.
- Calls `userService.setPassword(userId, newPassword)`, which validates against `PasswordPolicy`,
  bcrypts, **and bumps the user's token-version (revoking ALL prior sessions)**. The comment is
  explicit: do *not* bump again here, to avoid a double-bump. Marks the token `usedAt = now`.

**`me()` — `GET /me`.** Authenticated profile: `SecurityUtils.requireUser()` → reload `User` →
gather `OrgMember` memberships (resolving each org's slug/name) → returns `MeResponse(userDto, orgs,
permissions)` where `permissions` is the principal's authority set. *Gotcha:* it issues one
`organizationRepository.findById` per membership (N+1) — fine at admin scale, watch it grow.

**Private helpers.** `recordLoginFailed(...)` writes an `auth.login.failed` FAILED audit row directly
(login throws before the aspect's success path) and is fail-open (login still returns 401 even if the
audit hiccups) — it marks recorded to dedupe. `maskEmail` masks PII in audit metadata (`a***@x.com`,
or `***@x.com` for very short locals). `generateRawToken`/`sha256` produce the reset token + its hash
(Base64-url, no padding; `NoSuchAlgorithmException` → `IllegalStateException`).

#### Records (request/response DTOs, declared inside the controller)
- `LoginRequest(@NotBlank @Email String email, @NotBlank String password)`.
- `LoginResponse(accessToken, expiresAt, user, mfaRequired, mfaChallenge, mfaChallengeExpiresAt)` —
  `@JsonInclude(NON_NULL)`, a **unified** response. Static factories: `session(...)` →
  `mfaRequired=false` with the token; `mfaChallenge(...)` → `mfaRequired=true`, no token, just the
  challenge + its expiry. The client keys off `mfaRequired` to decide whether to call `/mfa/login`.
- `MfaLoginRequest(@NotBlank challenge, @NotBlank code)`.
- `PasswordResetRequest(@NotBlank @Email email)`.
- `PasswordResetConfirm(@NotBlank token, @NotBlank @Size(min=8,max=255) newPassword)` — note the bean
  `@Size` is a coarse guard; the real strength check is `PasswordPolicy` inside `UserService.setPassword`.
- `OrgMembershipDto(orgId, slug, name, role)` and `MeResponse(user, orgs, permissions)`.

---

### `SecurityConfig.java`
**`@Configuration @EnableWebSecurity @EnableMethodSecurity(prePostEnabled=true)`** — assembles the
filter chains, beans for the custom filters, and CORS. Method-level `@PreAuthorize`/`@PostAuthorize`
is enabled here (the actual rules live on the various service/controller methods across the codebase).

Constructor injects `SessionTokenService`, `AuthoritiesLoader`, `ObjectMapper`,
`SessionRevocationStore`, `UserRepository`, `TrustedProxyResolver`, plus two `@Value`s:

| Property | Default | Purpose |
|---|---|---|
| `app.auth.revocation.enabled` | `true` | Master switch for the `JwtAuthFilter` denylist + token-version fast-path checks. |
| `app.cors.allowed-origins` | `http://localhost:5173` | Comma-separated allowed origins (Vite dev server default). Bound to `List<String>`. |

#### Beans

**`jwtAuthFilter()`** — constructs `JwtAuthFilter` with all its collaborators (passing
`revocationEnabled`).

**`jwtAuthFilterDisabledAuto(...)` / `rateLimitFilterDisabledAuto(...)`** — each returns a
`FilterRegistrationBean` with `setEnabled(false)`. **Why this exists (gotcha):** Spring Boot
auto-registers any `Filter` bean into the *servlet* container, which would run these filters a
*second* time outside the Security chain. Disabling auto-registration ensures they run **only** where
explicitly added inside the chains. Forget this and `JwtAuthFilter` runs twice.

**`rateLimitFilter(...)`** — builds the `com.example.cp.common.RateLimitFilter` (per-IP token bucket).
Reads `app.ratelimit.auth.capacity` (default 10) and `app.ratelimit.auth.refill-per-minute`
(default 10). It self-restricts to the 4 sensitive auth POST paths via its own `shouldNotFilter`.

**`ssoFilterChain(...)` — `@Order(1)`.** The browser-SSO chain, scoped via `securityMatcher` to
**only** the OAuth2/SAML login + callback + SP-metadata paths. The `oauth2Login`/`saml2Login` entry
points (which redirect to the IdP) are installed *here* and nowhere else, so a credential-less
`/api/**` call returns a 401 JSON ProblemDetail instead of a 302 to the OIDC authorization endpoint.
CSRF disabled, CORS applied, `anyRequest().permitAll()`. The `SsoSuccessHandler` is wired in only when
present (`ObjectProvider.getIfAvailable()`), so the chain still builds when SSO is not configured.

**`securityFilterChain(...)` — `@Order(2)`.** The main **stateless** API chain:
- `csrf().disable()` (token/Bearer + `SameSite=Lax` cookie model — no server session to protect),
  CORS from `corsConfigurationSource()`, `SessionCreationPolicy.STATELESS`.
- **Authorization rules** (order matters — most specific first):
  - `permitAll`: `/api/v1/auth/**`, license CRL/revoked lists, `/.well-known/jwks.json`,
    `/actuator/health[/**]`, `/actuator/info`.
  - `/actuator/**` → `hasAuthority("SUPER_ADMIN")` — sensitive actuator (metrics/prometheus/env)
    is restricted to super-admins, *not* "any authenticated principal" (which would include tenant
    users and customer API keys).
  - Swagger/OpenAPI paths → `authenticated()` (the API surface inventory is not handed to anonymous).
  - `/api/**` and `anyRequest()` → `authenticated()`.
- `authenticationEntryPoint(HttpStatusEntryPoint(401))` — credential-less protected requests get a
  bare 401, never an IdP redirect.
- **Security headers**: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, HSTS
  (`includeSubDomains`, 1-year max-age), `Referrer-Policy: no-referrer`, and a strict CSP
  `default-src 'none'; frame-ancestors 'none'` (this is a JSON API, not an HTML app).
- **Filter ordering:** `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`
  then `addFilterBefore(rateLimitFilter, JwtAuthFilter.class)`. The comment explains the sequencing:
  add JWT first so `JwtAuthFilter.class` has a registered order, then anchor the rate limiter *before*
  it (rate limiting runs before auth). `ApiKeyAuthFilter` (if present) is also added before
  `JwtAuthFilter`. `httpBasic` and `formLogin` are disabled.

**`corsConfigurationSource()`.** Origins from `corsAllowedOrigins`; methods GET/POST/PUT/PATCH/
DELETE/OPTIONS; allowed headers include `Authorization`, `Idempotency-Key`; exposes `Authorization`
and `Content-Disposition`; `allowCredentials(true)`; 1-hour preflight cache. **Gotcha** (called out in
the code): `allowCredentials(true)` is incompatible with a wildcard `*` origin — never set `*` here.

---

### `JwtAuthFilter.java`
**`extends OncePerRequestFilter`** — the per-request authenticator on the stateless chain. Turns a
session JWT (from the Bearer header *or* the `cp_session` cookie) into a populated `SecurityContext`,
or writes an RFC-7807 `ProblemDetail` and short-circuits the request.

Constructor: `SessionTokenService`, `AuthoritiesLoader`, `ObjectMapper`, `SessionRevocationStore`,
`UserRepository`, `boolean revocationEnabled`, `TrustedProxyResolver`. Constant
`SESSION_COOKIE = "cp_session"`.

#### `doFilterInternal(request, response, chain)` — the validation pipeline
1. **Token extraction.** Bearer `Authorization` header first; if absent, the `cp_session` cookie.
   No token at all → `chain.doFilter` continues anonymously (authorization rules later return 401 if
   the endpoint needs auth).
2. **Parse + signature/issuer/purpose/expiry.** `tokenService.parse(token)`; any `ApiException` →
   `writeProblem(...)` and **return** (do not continue the chain).
3. **`jti` denylist check** (single-session logout) — only when `revocationEnabled` and a `jti` is
   present and `revocationStore.isJtiDenylisted(jti)` → 401 "Session has been revoked".
4. **Reload the user — UNCONDITIONAL.** `userRepository.findById(parsed.userId())`. Missing user →
   401 "User not found"; `Status != ACTIVE` → 401 "Account is not active". These run even when
   revocation is disabled, so a suspended/deleted user is rejected immediately regardless of Redis.
5. **Token-version (bulk revocation) check** — when `revocationEnabled`: the DB
   `user.getTokenVersion()` is the source of truth; the cached Redis value (≥0) only accelerates and
   is `-1` on miss. `effectiveVersion = max(db, cached)`; if `parsed.tokenVersion() < effectiveVersion`
   → 401 "Session has been revoked". *This is how "log out everywhere" / password-reset / suspend
   instantly invalidate all outstanding tokens.*
6. **Fresh authorities & super-admin.** Both are resolved from the *reloaded* user — never trusted
   from the JWT claims — closing the gap where a stale `super_admin`/`authorities` claim would keep
   granting access until expiry. Builds an `AuthenticatedUser(userId, email, superAdmin,
   authorityCodes, grantedAuthorities)` principal and sets it in the `SecurityContextHolder` via a
   `UsernamePasswordAuthenticationToken`.
7. **Audit wiring.** `AuditContext.setActor(userId, null)` and `setIp(proxyResolver.resolveClientIp)`,
   then `chain.doFilter(...)` inside a `try/finally` that always calls `AuditContext.clear()`
   afterwards (the context is thread-local; clearing prevents bleed across pooled threads).

#### `writeProblem(response, status, title, detail)`
Sets `AuditContext.setOutcome(DENIED)`, writes a `ProblemDetail` as
`application/problem+json` via the injected `ObjectMapper`. Centralizes every rejection so denials
are uniform and auditable.

**Gotchas for a new engineer:**
- This filter only ever *rejects* or *populates context*; it does not enforce path authorization —
  that's `authorizeHttpRequests` + `@PreAuthorize`.
- The DB reload + authorities resolution happen on **every** request — caching is in the
  `AuthoritiesLoader` layer, not here.
- The claims-in-token (`super_admin`, `authorities`) are essentially decorative for authorization;
  the live user state always wins.

---

### `SessionTokenService.java`
**`@Service`** — mints and verifies the HS256 session JWT. The token is the shared currency between
`AuthController` (issue), the `cp_session` cookie, `JwtAuthFilter` (parse), and `logout` (parse for
the `jti`).

Constants: `ISSUER = "control-panel"`, `PURPOSE = "session"` (claim `purpose`). Constructor reads:

| Property | Default | Purpose |
|---|---|---|
| `app.auth.session-secret` (`APP_AUTH_SESSION_SECRET`) | *(empty → fails fast)* | HS256 signing secret. |
| `app.auth.session-ttl` | `PT30M` | Token lifetime (`Duration`). |

**`@PostConstruct validate()`** — fails fast on startup if the secret is blank or `< 32 bytes`
(HS256 minimum). This is a deliberate boot-time guard: a missing/weak secret is a configuration error,
not a runtime surprise.

**`issue(userId, email, superAdmin, authorities, tokenVersion) → IssuedToken`.** Builds a
`JWTClaimsSet`: `iss=control-panel`, `sub=userId`, `email`, `purpose=session`, `super_admin`,
`authorities` (the set **joined by commas** into one compact string — keeps the JWT small), `tv` =
token-version, `iat`, `exp = now + ttl`, and a random `jti` (UUID). Signs with `MACSigner` over the
UTF-8 secret. `JOSEException` → `IllegalStateException` (signing must not fail silently).

**`parse(token) → ParsedToken`.** The verification gauntlet, in order: blank → 401 "Missing token";
parse + `MACVerifier.verify` → 401 "Invalid token signature"; **issuer must equal `control-panel`**
→ 401 "Invalid token issuer"; **`purpose` must equal `session`** → 401 "Invalid token purpose";
expiry check → 401 "Token expired". Then it reconstructs the principal data: `userId` from `sub`,
`email`, `super_admin` (null-safe), `authorities` (split the compact string back into a
`LinkedHashSet`), `jti`, and `tv` (default 0). Any `ParseException`/`JOSEException` → generic 401
"Invalid token".

> **Security gotcha — purpose/issuer pinning.** The `MfaService` MFA-challenge token is *also* an
> HS256 JWT signed with the **same** session secret. The only thing stopping an attacker from
> replaying a challenge as a session (or vice-versa) is that `parse` *requires* `purpose=session` and
> `iss=control-panel`, while the challenge carries `purpose=mfa_challenge`. Never relax these checks.

Records: `IssuedToken(token, expiresAt)` and `ParsedToken(userId, email, superAdmin, authorities,
expiresAt, jti, tokenVersion)`. `ttl()` exposes the configured duration (used to set the cookie
`Max-Age`).

---

## Session revocation

The control panel has **two independent revocation axes**, both abstracted behind
`SessionRevocationStore` and selected by `SessionRevocationConfig`:

| Axis | Granularity | Trigger | Store key | Read by |
|---|---|---|---|---|
| `jti` denylist | one token | `POST /logout` | `cp:sess:denylist:jti:{jti}` (TTL = remaining life) | `JwtAuthFilter` step 3 |
| token-version | all of a user's tokens | password change/reset, suspend, delete (`UserService.revokeAllSessions`) | `cp:sess:tokenver:{userId}` (no TTL) + `users.token_version` DB column | `JwtAuthFilter` step 5 |

The DB `users.token_version` column is the **durable source of truth** for the bulk axis; Redis is a
best-effort accelerator (a write-through that may be `-1`/missing without affecting correctness,
because the filter falls back to the DB compare).

### `SessionRevocationStore.java`
Interface with four methods. Contract notes baked into the Javadoc:
- `denylistJti(String jti, Duration ttl)` — the TTL **must** equal `exp - now` so the key
  self-expires; never store with infinite TTL.
- `boolean isJtiDenylisted(String jti)`.
- `setTokenVersion(UUID userId, long version)` — write-through cache of the DB column.
- `long currentTokenVersion(UUID userId)` — returns **`-1` on miss/error** so callers fall back to
  the DB value. This sentinel convention is load-bearing in `JwtAuthFilter`.

### `RedisSessionRevocationStore.java`
Redis impl over `StringRedisTemplate`. Keys `cp:sess:denylist:jti:{jti}="1"` (PX = remaining life)
and `cp:sess:tokenver:{userId}` (Long, no TTL). **The fail modes are the interesting part and differ
per method by design:**

| Method | On Redis error | Rationale |
|---|---|---|
| `isJtiDenylisted` | **fail CLOSED → returns `true`** | An outage means "cannot confirm not-revoked"; treat as revoked. |
| `denylistJti` | **throws `RevocationStoreException`** | A logout that can't persist must *not* report success; caller → 503. |
| `currentTokenVersion` | returns `-1` | Filter falls back to durable DB token-version compare. |
| `setTokenVersion` | logs & swallows | DB column is authoritative; cache write is best-effort. |

> Note the asymmetry: the *availability-preserving* login rate limiter fails **open**, while the
> *security-preserving* revocation reads fail **closed**. Both are deliberate.

### `InMemorySessionRevocationStore.java`
Fallback (tests / no-Redis). `ConcurrentHashMap` denylist (`jti → expiry Instant`) + versions map.
**Bounded:** `MAX_DENYLIST = 100_000`; on write it prunes already-expired entries and, at the cap,
evicts the oldest-expiring entry (it would self-expire on read anyway) — so a logout flood can't grow
memory unbounded. `isJtiDenylisted` lazily removes expired entries. Per-JVM and non-durable: a restart
forgets denylisted jtis (acceptable for the single-instance/test profile; bulk revocation via the DB
column is unaffected).

### `SessionRevocationConfig.java`
**`@Configuration`** providing exactly one `SessionRevocationStore` bean. Uses
`ObjectProvider<RedisConnectionFactory>.getIfAvailable()` → Redis impl when present, else in-memory;
logs which path was chosen. **Why `ObjectProvider` and not `@ConditionalOnBean`:** that's the
remediated finding **P2** — a component-scanned `@Component @ConditionalOnBean(RedisConnectionFactory)`
is evaluated *before* `RedisAutoConfiguration` registers the factory, so the condition silently never
matched and the in-memory store was **always** selected, even in production. The explicit
`ObjectProvider` wiring at bean-definition time is deterministic. (`LoginRateLimiterConfig` mirrors
this exact pattern.)

### `RevocationStoreException.java`
A `RuntimeException` raised only by `RedisSessionRevocationStore.denylistJti` when the durable
revocation write fails. `AuthController.logout` catches it and maps it to **503** so the client
retries — the contract being "never report a successful logout while the token remains valid."

---

## Login brute-force throttle (per-account + per-IP lockout)

This is **separate** from the per-IP request token-bucket (`RateLimitFilter`). The token bucket caps
*request rate*; this lockout caps *failed-credential attempts* and locks the account.

### `LoginRateLimiter.java`
Interface. Contract: `isLocked(email, ip)` is consulted *before* checking credentials;
`recordFailure(email, ip)` on every failed attempt (unknown user, bad password, inactive, bad MFA
code); `recordSuccess(email, ip)` clears the per-account counter on success. `isLocked` returns true
if the **account** is locked OR the **IP** has exceeded its per-IP failure ceiling within the window.

### `RedisLoginRateLimiter.java`
Cluster-safe impl over `StringRedisTemplate`. Keys (all self-expiring — never stored without a TTL):
- `cp:login:fail:acct:{email}` — `INCR`, `EXPIRE = window` (set only when the count first hits 1).
- `cp:login:lock:acct:{email}` — set to `"1"` with `EXPIRE = lockout` once failures `>= maxAttempts`.
- `cp:login:fail:ip:{ip}` — `INCR`, `EXPIRE = window`; `isLocked` trips when the value `>= perIpMax`.

Config (also read by `LoginRateLimiterConfig`):

| Property | Default | Meaning |
|---|---|---|
| `app.auth.lockout.max-attempts` | `5` | Per-account failures within the window before lockout. |
| `app.auth.lockout.window` | `PT5M` | Sliding-window length for counting failures. |
| `app.auth.lockout.lockout` | `PT15M` | How long a locked account stays locked. |
| `app.auth.lockout.per-ip-max` | `20` | Per-IP failure ceiling within the window (spray mitigation). |

**Fail mode: every Redis call fails OPEN** — a transient outage degrades to "not locked / not
recorded" so an outage can't lock the entire user base out of login. Emails are normalized
(`trim().toLowerCase()`) for keying. `recordSuccess` deletes both the account fail counter and the
lock key (but not the IP counter).

### `InMemoryLoginRateLimiter.java`
Single-instance/test fallback (not a `@Component`; instantiated by the config). Two
`ConcurrentHashMap<String, Counter>` (accounts, ips); `Counter{windowStart, failures, lockedUntil}`.
- **Accounts** support a hard lockout: on the `maxAttempts`-th failure within the window,
  `lockedUntil = now + lockoutDuration`.
- **IPs** are a rolling count with the `perIpMax` ceiling only — no lockout timer.
- `recordOn` resets the counter when the window has elapsed (a fresh sliding window). `recordSuccess`
  removes the account entry (clearing both failures and lock). Email keying is normalized as above.
- *Caveat (documented):* counters aren't shared across instances, so use Redis in multi-instance
  deploys.

### `LoginRateLimiterConfig.java`
**`@Configuration`** — the single `LoginRateLimiter` bean via `ObjectProvider<RedisConnectionFactory>`
(same deterministic pattern and same P2 rationale as `SessionRevocationConfig`). Redis impl when a
factory is present, in-memory otherwise. Builds the `StringRedisTemplate` itself
(`afterPropertiesSet()`).

### `LoginAttempt.java` — *deprecated*
`@Deprecated(forRemoval = true) @Component`. A thin backward-compat delegate exposing the old
per-email-only API (`isLocked(email)`, `recordFailure(email)`, `recordSuccess(email)`) by calling the
real `LoginRateLimiter` with a **`null` IP**, so it only exercises the per-account path. **Gotcha:** a
`null` IP means it bypasses the per-IP ceiling entirely — new code must use `LoginRateLimiter`
directly. It exists only so any lingering caller keeps compiling.

---

## Passwords

### `PasswordConfig.java`
**`@Configuration`** with a single `@Bean PasswordEncoder` → `BCryptPasswordEncoder(12)` (cost factor
12). This is the encoder injected into `AuthController` (login `matches`) and `UserService`
(`encode`). Cost 12 is the deliberate work-factor; raising it is the lever for future hardening.

### `PasswordPolicy.java`
**`@Component`** — the centralized strength policy enforced by `UserService.createUser/setPassword/
changePassword` (and thus the reset-confirm path). `validate(String)` throws `ApiException` (400) on
the **first** failing rule:
- `MIN_LENGTH = 12`, `MAX_LENGTH = 72`. The 72 cap is meaningful: **bcrypt silently truncates beyond
  72 bytes**, so the policy rejects longer inputs to avoid that surprise.
- Must contain an uppercase, a lowercase, a digit, and a **symbol** (anything non-alphanumeric — incl.
  whitespace/punctuation/unicode counts).
- Must not be in the small `COMMON` denylist (e.g. `password123!`, `welcome12345`) — explicitly *not*
  a substitute for a full HIBP/breached-password check; it just blocks obvious offenders that still
  satisfy the character-class rules. Comparison is case-insensitive (`password.toLowerCase()`).

*Gotcha:* the `PasswordResetConfirm` record's bean-validation `@Size(min=8,max=255)` is laxer than
this policy; the authoritative check happens inside `UserService.setPassword`, so an 8-char password
that passes bean validation will still be rejected (min 12) at the service layer.

### `PasswordResetToken.java`
JPA `@Entity` → table `password_reset_tokens`. Lombok `@Getter/@Setter/@Builder/@NoArgs/@AllArgs`.
Columns: `id` (UUID PK), `user_id`, `token_hash` (length 255 — stores only the **SHA-256 hash** of the
raw token, never the raw token), `expires_at`, `used_at` (nullable; set on single-use consumption),
`created_at`. The single-use + hashed-at-rest + 60-minute-TTL design means a leaked DB row can't be
turned into a working reset link.

### `PasswordResetTokenRepository.java`
`@Repository extends JpaRepository<PasswordResetToken, UUID>`. One derived query:
`findByTokenHash(String) → Optional`. The controller hashes the presented raw token and looks it up
by hash — the lookup itself never sees plaintext.

---

## Cross-cutting gotchas & invariants (quick reference)

- **Live state beats claims.** Authorization always re-derives super-admin + authorities from the
  reloaded `User`; JWT claims are a hint. Don't add a fast-path that trusts the claims.
- **Two rate limiters, two purposes.** `RateLimitFilter` (per-IP request token bucket, in `common`)
  vs. `LoginRateLimiter` (per-account/per-IP failed-credential lockout, this package). Both key off
  `TrustedProxyResolver` for a non-spoofable client IP.
- **Fail-open vs fail-closed is intentional and opposite** for rate-limiting (open, preserve login
  availability) vs. revocation reads (closed, preserve security).
- **Cookie + Bearer parity.** `JwtAuthFilter` and `AuthController.logout` resolve the token with the
  identical precedence (Bearer header → `cp_session` cookie). Keep them in sync or SSO/cookie sessions
  become unrevocable.
- **`-1` sentinel** from `currentTokenVersion` is the contract for "cache miss → use the DB". Don't
  return `0`.
- **`ObjectProvider`, not `@ConditionalOnBean`** for choosing Redis-vs-memory impls (finding P2). Two
  config classes (`SessionRevocationConfig`, `LoginRateLimiterConfig`) follow this; copy the pattern.
- **`FilterRegistrationBean(...).setEnabled(false)`** prevents the custom filters from running twice
  (once auto-registered by Boot, once in the Security chain).
- **MFA challenge vs session token** share the secret; the `purpose`/`issuer` pinning in
  `SessionTokenService.parse` is the only barrier between them.
