# Architecture — Cross-Cutting Narrative

> **Scope of this document.** This is the conceptual heart of the documentation: the
> *end-to-end* story of how a request, a session, a license, an event, and a key move
> through the system. It reads broadly across `control-panel-api` (the Spring Boot 3.3 /
> Java 21 REST API) and the two verifier modules (`license-verifier`, the offline SDK, and
> `license-verifier-spring-boot-starter`, its auto-config). Per-file reference docs live in
> the per-area module docs; here we explain *flow and rationale* — why the pieces are
> arranged the way they are, what guarantees each boundary provides, and the security /
> concurrency reasoning baked into each decision.

---

## 0. The system in one paragraph

The **control panel** is a multi-tenant SaaS back office. Human admins and machine API
keys call a stateless REST API to manage **organizations**, **plans**, **subscriptions**,
and **users**. The product it sells is **offline-verifiable software licenses**: the panel
mints an Ed25519-signed JWT (wrapped in a `.lic` envelope), the customer drops that file
into their own Docker app, and the app's bundled **verifier SDK** validates it *with no
network call to the panel* — signatures are checked against a published JWKS, and
revocation is propagated out-of-band through a signed **CRL**. Everything the panel does
that matters (issue, revoke, rotate keys, change RBAC) is **audited**, emitted to a
**transactional outbox**, and fanned out to per-tenant **webhooks**. Secrets at rest
(signing private keys, TOTP secrets, webhook HMAC secrets, OIDC client secrets) are
envelope-encrypted under a rotatable KEK.

The two halves never share a database. The only contract between the panel and a customer
app is **three signed artifacts over HTTP**: the **JWKS** (`/.well-known/jwks.json`), the
**license file** (issued once, then a static download), and the **CRL**
(`/api/v1/licenses/crl`). That is the entire trust boundary.

```
            ┌──────────────────────── CONTROL PANEL (online) ────────────────────────┐
            │  React admin UI ──HTTPS──▶ control-panel-api ──▶ PostgreSQL             │
            │                                   │  ▲                                  │
            │                                   │  └── Redis (denylist / lockout)     │
            │            issues .lic            ▼                                     │
            │          publishes JWKS + CRL ───────────────────────────────────┐     │
            └──────────────────────────────────────────────────────────────────┼─────┘
                                                                                │ HTTP (pull)
            ┌──────────────────────── CUSTOMER APP (offline-capable) ───────────┼─────┐
            │  sample-docker-app                                                ▼     │
            │   └─ license-verifier-spring-boot-starter (auto-config + @RequiresPermission)
            │        └─ license-verifier SDK  ──verifies signature, exp, aud, CRL──┘  │
            │             reads /etc/app/license.lic + cached JWKS + cached CRL       │
            └────────────────────────────────────────────────────────────────────────┘
```

---

## 1. The HTTP request lifecycle

Every request to `control-panel-api` runs through a deliberately ordered gauntlet. The
ordering matters: cheap rejections happen first, identity is established before
authorization, and forensics (correlation id, audit) bracket the whole thing so even a
request rejected at the door is observable.

### 1.1 The filter chain, in order

There are **two** kinds of filters: servlet filters auto-registered on the container
(ordered by `@Order`), and Spring-Security-internal filters living inside the
`Order(2)` `SecurityFilterChain`. They interleave as follows for an `/api/**` request:

```
  ┌─ servlet container filter chain (ordered by @Order) ───────────────────────────┐
  │ 1. CorrelationIdFilter        @Order(HIGHEST_PRECEDENCE)      bind requestId MDC │
  │ 2. RequestSizeLimitFilter     @Order(HIGHEST_PRECEDENCE+5)    413 if body too big│
  │ 3. AccessLogFilter            @Order(HIGHEST_PRECEDENCE+10)   one line / request │
  │ 3. IdempotencyBodyCachingFilter (HIGHEST_PRECEDENCE+10)       buffer body/resp   │
  │ 4. Spring Security FilterChainProxy ──────────────────────────────────────────┐ │
  │    ├─ (Order 1 chain) SSO entry points — ONLY for /oauth2|/saml2 paths         │ │
  │    └─ (Order 2 chain) the stateless API chain:                                 │ │
  │         a. RateLimitFilter        (before JwtAuthFilter) token bucket / IP      │ │
  │         b. ApiKeyAuthFilter       (before JwtAuthFilter) "ApiKey <raw>"         │ │
  │         c. JwtAuthFilter          (before UsernamePasswordAuthFilter)           │ │
  │         d. AuthorizationFilter    authorizeHttpRequests(...) coarse gate        │ │
  │    └────────────────────────────────────────────────────────────────────────┘ │
  │ 5. DispatcherServlet ─▶ IdempotencyInterceptor.preHandle                        │
  │      ─▶ @PreAuthorize method security ─▶ Controller ─▶ Service ─▶ Repository/DB │
  │      ◀─ IdempotencyInterceptor.afterCompletion                                  │
  └─────────────────────────────────────────────────────────────────────────────────┘
```

A subtle but important wiring detail in `SecurityConfig`: `JwtAuthFilter` and
`RateLimitFilter` are declared as `@Bean`s **and** given disabled
`FilterRegistrationBean`s. That second registration *prevents* Spring Boot from also
auto-registering them on the raw servlet chain — they must run **only inside the security
chain**, where ordering relative to `UsernamePasswordAuthenticationFilter` is controlled.
Without the disabling bean each filter would execute twice.

### 1.2 Step-by-step, with rationale

| # | Stage | What it does | Why it is *here* (rationale / edge cases) |
|---|-------|--------------|-------------------------------------------|
| 1 | **CorrelationIdFilter** | Reads inbound `X-Request-Id` (allow-listed charset, ≤64 chars) or mints a UUID; binds it to SLF4J `MDC[requestId]`; echoes it on the response; clears MDC in `finally`. | Runs *first* so every downstream log line — including a 401 from the auth filters — carries the same id. The `finally` clear stops the value leaking onto a pooled worker thread. The header is echoed *before* `chain.doFilter` so it is present even if a downstream filter commits the response early. |
| 2 | **RequestSizeLimitFilter** | For POST/PUT/PATCH, rejects with `413` (hand-rolled RFC-7807 JSON) when declared `Content-Length` > `app.request.max-body-size` (256 KB). | Container multipart limits do not cap a large `application/json` body. Unauthenticated `POST /auth/login` would otherwise buffer + Jackson-parse multi-MB bodies — a cheap memory/CPU amplification. Runs *before* body buffering. Chunked requests (no `Content-Length`) pass and are bounded by container stream limits. |
| 3 | **AccessLogFilter** / **IdempotencyBodyCachingFilter** | Access log: one structured line (method, path, status, latency, resolved client IP) in a `finally`. Body-caching: wraps request+response *only* when a POST/PUT/PATCH carries a non-blank `Idempotency-Key`. | Access log runs after correlation so `requestId` is already in MDC; health probes are excluded to avoid flooding. The caching wrapper makes the request body replayable so both the idempotency interceptor (hash) and the controller (bind) see it. |
| 4a | **RateLimitFilter** | Per-client-IP token bucket (bucket4j core) for the 4 sensitive auth paths only (`shouldNotFilter` self-restricts). `429` + `Retry-After` when empty. Bounded LRU of 50k buckets. | Client IP comes from `TrustedProxyResolver` so each end-user (not the shared proxy IP) gets a bucket. Per-instance/in-memory; the *cluster-wide* brute-force lockout is `LoginRateLimiter` (Redis in prod). |
| 4b | **ApiKeyAuthFilter** | If `Authorization: ApiKey <raw>`, verify the key, build an `ApiKeyAuthentication` whose principal is an `AuthenticatedUser` with `apiKey=true` and the key's bound `orgId`, authorities = the key's scopes. | Runs before `JwtAuthFilter`. A failed key auth is *swallowed* (logged) — it does not 401 here; the request simply continues unauthenticated and the coarse gate / method security rejects it. |
| 4c | **JwtAuthFilter** | Bearer header *or* `cp_session` cookie → parse the session JWT → denylist + status + token-version + fresh-authorities checks → set `SecurityContext` + `AuditContext`. See §2.4. | The single human-auth choke point. Authorities are **always** re-resolved from current DB state, not the frozen claim. |
| 4d | **AuthorizationFilter** | `authorizeHttpRequests`: a small `permitAll` allow-list (auth endpoints, CRL, JWKS, health), `/actuator/**` → `SUPER_ADMIN`, swagger → authenticated, everything else `/api/**` → authenticated. | A *coarse* gate. Resource-level tenant checks are deferred to `@PreAuthorize`. Credential-less protected requests get a **401 JSON** (never a 302 to an IdP — those entry points live on the separate SSO chain). |
| 5 | **IdempotencyInterceptor.preHandle** | If `Idempotency-Key` present on a mutating request: replay a completed result, `409` an in-flight one, `422` a key-reuse-with-different-body, else claim the key. See §7.1. | Runs after auth so the *actor* is part of the natural key (callers cannot replay each other's responses). |
| 6 | **@PreAuthorize** | SpEL method security, almost always delegating to the `@tenantAccess` bean (`TenantAccessChecker`). See §3. | This is the *real* tenant-isolation boundary; the filter-chain gate only proves "authenticated". |
| 7 | **Controller → Service → Repository → DB** | Business logic. Services are `@Transactional`; controllers stamp `AuditContext`. | — |

### 1.3 Exception mapping (the way out)

All errors converge on `GlobalExceptionHandler` (`@RestControllerAdvice` extending
`ResponseEntityExceptionHandler`), which renders RFC-7807 `ProblemDetail` bodies:

```
 ApiException (status carried)            ─▶ that status; details are caller-safe, passed through
   └─ 401/403 ............................─▶ also writes a DENIED audit row (REQUIRES_NEW)
 AccessDeniedException ...................─▶ 403 "Access is denied" (generic; reason logged only)
 AuthenticationException .................─▶ 401 "Authentication required"
 OptimisticLockingFailureException .......─▶ 409 + {"retryable": true}   (a @Version race — retry)
 DataIntegrityViolationException .........─▶ 409 (check-then-insert lost a UNIQUE race)
 MethodArgumentNotValidException .........─▶ 400 + per-field {errors}
 IllegalArgumentException ................─▶ 400 (raw message NOT echoed — logged)
 (framework MVC exceptions via base class)─▶ 400/404/405/406/415 as appropriate
 Exception (catch-all) ...................─▶ 500 "An unexpected error occurred" (message hidden)
```

Two design rules run through this:
1. **Never leak internals.** Only `ApiException` details and bean-validation messages
   (both author-controlled) reach the client; everything else is generic + logged.
2. **Auth refusals are auditable even when thrown deep.** A 401/403 raised inside a
   controller unwinds its rolling-back business transaction; the handler then commits a
   durable `DENIED` row in a fresh transaction (and clears the `AuditContext` thread-local
   so its `recorded` sentinel cannot leak to the next pooled request).

Filters that reject *before* MVC (request-size, rate-limit, JWT) bypass this advice and
write their own hand-rolled RFC-7807 JSON directly to the response — there is no
`DispatcherServlet` in scope yet.

---

## 2. Authentication & the session model

The panel supports **two** machine/human auth schemes and one browser SSO path, all
converging on the same `AuthenticatedUser` principal and the same `SecurityContext`.

### 2.1 The session token

`SessionTokenService` mints/parses a compact **HS256 JWT** (symmetric, signed with
`app.auth.session-secret`, ≥32 bytes enforced at startup). It is *not* the Ed25519 license
key — these are different cryptosystems for different purposes (license keys are
asymmetric so customers can verify offline; the session secret never leaves the panel).

Claims: `iss=control-panel`, `sub=<userId>`, `email`, **`purpose=session`**,
`super_admin`, `authorities` (CSV), **`tv`** (token-version), `jti`, `iat`, `exp`
(30 min default). On parse, **issuer and `purpose` are mandatory** — this is what stops an
`mfa_challenge` token (signed with the *same* secret but `purpose=mfa_challenge`) from
ever being replayed as a session.

### 2.2 Two-step login (password + MFA)

```
 POST /auth/login {email, password}
   ├─ rate-limit (RateLimitFilter) + lockout check (LoginRateLimiter, Redis)
   ├─ verify password (bcrypt)             ── fail ─▶ recordFailure + 401 (generic message)
   ├─ MFA enabled?
   │     ├─ NO  ─▶ completeSession() ─▶ {accessToken, expiresAt, user} + Set-Cookie cp_session
   │     └─ YES ─▶ issue short-lived mfa_challenge JWT (5 min, purpose=mfa_challenge)
   │              return {mfaRequired:true, mfaChallenge, mfaChallengeExpiresAt}  (NO session yet)
   │
 POST /auth/mfa/login {challenge, code}
   ├─ challenge is MANDATORY (it is the only proof step-1 passed — keeps MFA a true 2nd factor)
   ├─ parse challenge ─▶ userId;  lockout check BEFORE verifying the code (no unlimited TOTP guesses)
   ├─ verifyLoginCode (TOTP, ±1 step skew, monotonic last-accepted-step replay guard)
   └─ completeSession()
```

Key properties:
- **The MFA challenge is not a session.** It carries `purpose=mfa_challenge`, so
  `SessionTokenService.parse` rejects it, and the API only accepts it at `/auth/mfa/login`.
- **TOTP replay is blocked.** `MfaService.verifyAndAdvanceStep` scans the skew window
  newest-step-first, constant-time-compares, and on a match advances
  `UserMfa.lastAcceptedStep` so a code valid at multiple steps consumes the latest and can
  never be reused.
- **Brute force is throttled on both factors.** `/auth/mfa/login` shares the per-account +
  per-IP lockout with `/login`, consulted *before* code verification.
- **Failure is silent.** `unknown_user`, `bad_password`, `inactive` all return the same
  generic `401 Invalid email or password` (no user-enumeration), but each is recorded
  internally with a distinct reason (email masked).

### 2.3 SSO (OIDC / SAML) with JIT provisioning

A *separate* `Order(1)` `SecurityFilterChain` (`ssoFilterChain`) owns the
`/oauth2/authorization/**`, `/login/oauth2/**`, `/saml2/**` paths. This is deliberate:
the IdP-redirecting entry points live *only* here, so a credential-less `/api/**` call on
the stateless chain returns a 401 JSON instead of a 302 to the OIDC authorization
endpoint.

On success, `SsoSuccessHandler` runs a gauntlet of account-takeover guards, then mints a
normal `cp_session` cookie:

```
 IdP callback ─▶ SsoSuccessHandler.onAuthenticationSuccess
   1. extract email, name, stable subject (OIDC sub / SAML NameID — NEVER the email)
   2. reject if email missing
   3. reject if OIDC email_verified=false (account-takeover guard #47)
   4. is (provider, subject) ALREADY bound?  ── the strong, durable identity key (#48)
        ├─ bound      ─▶ skip the new-binding gates (a hostile IdP changing the email it
        │                 sends cannot hijack another account)
        └─ not bound  ─▶ require a matched provider AND email-domain on the provider's
                          verified allow-list (#40) before JIT/auto-link
   5. SsoProvisioningService.provision(...)  ── ONE @Transactional:
        user create-or-link  +  (provider, subject) identity binding  +  org membership
        +  all their audit rows.  A mid-sequence failure rolls back the WHOLE provisioning.
   6. issueSessionCookie(): mint a cp_session JWT, set HttpOnly/Secure/SameSite=Lax
   7. redirect to the UI with ?sso=success
```

The pre-2024 bug this closes: `SsoSuccessHandler` used to be a transaction-script where
each `save` committed independently, so a failure between the user insert and the identity
insert left a user with no identity binding. Moving the three writes into
`SsoProvisioningService.provision` makes it all-or-nothing.

### 2.4 The `cp_session` cookie and the unified auth path

`completeSession` (and `SsoSuccessHandler`) set the session JWT *both* in the response
body (for Bearer/non-browser clients) *and* as a cookie:

```
 Set-Cookie: cp_session=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=<ttl>
```

`HttpOnly` keeps it out of JS-readable `localStorage` (mitigates XSS token theft, #32);
`SameSite=Lax` is the CSRF posture (the API is otherwise CSRF-disabled because it is a
Bearer/cookie JSON API, not a form app). `JwtAuthFilter` accepts **Bearer header first,
cookie second**, feeding both into the identical validation path:

```
 JwtAuthFilter.doFilterInternal
   token = Bearer header  ?? cp_session cookie     (else: continue unauthenticated)
   parsed = SessionTokenService.parse(token)        (sig + iss + purpose + exp)
   ── revocation gates (all but the denylist also run when revocation is OFF) ──
   1. denylist:    revocationEnabled && jti in Redis denylist        ─▶ 401 revoked
   2. reload user: not found / status != ACTIVE                      ─▶ 401
   3. token-ver:   parsed.tv < max(user.tokenVersion, redisCached)   ─▶ 401 revoked
   4. fresh authorities: super_admin + authorities RE-RESOLVED from the reloaded user
   ── build principal, set SecurityContext, set AuditContext (actor + client IP) ──
   finally: AuditContext.clear()
```

The crucial design choice: **steps 2 and 4 are unconditional.** Even with the Redis
fast-path disabled or down, a suspended/deleted user or a stale frozen-authorities claim
is rejected, because the user is reloaded and authorities recomputed on *every* request.
The frozen claim is never trusted for authorization.

### 2.5 Session revocation — three mechanisms

`SessionRevocationStore` (Redis-backed in prod via `SessionRevocationConfig`, in-memory
otherwise) provides two of three revocation channels:

| Mechanism | Granularity | How it works | Fail mode |
|-----------|-------------|--------------|-----------|
| **jti denylist** | single session | `POST /auth/logout` denylists the exact `jti` with TTL = remaining token life (self-expiring key). | `isJtiDenylisted` **fails closed** (Redis outage ⇒ treat as revoked). `denylistJti` **propagates** an error so logout returns 503 rather than falsely reporting success while the token stays valid. |
| **token-version (`tv`)** | all of a user's sessions | `UserService.revokeAllSessions` bumps `users.token_version` (DB = source of truth) and write-throughs to Redis. Triggered by password change/reset, deactivate, delete. `JwtAuthFilter` rejects any token whose `tv` is below the effective max. | Redis cache miss returns `-1` ⇒ filter falls back to the durable DB compare. The DB column is authoritative; Redis only accelerates. |
| **status check** | account | `JwtAuthFilter` rejects any non-`ACTIVE` user immediately on reload. | Always on (DB read). |

Logout resolves the token with the *same* precedence as the filter (Bearer then cookie) —
otherwise a cookie/SSO session could never be revoked — and always clears the cookie
(`Max-Age=0`) so the browser drops it even when the jti was already expired.

---

## 3. RBAC + multi-tenant isolation

There are **three orthogonal axes** of authority, and conflating them is the classic
source of the privilege-escalation / IDOR bugs this design avoids.

| Axis | Question it answers | Where it lives |
|------|---------------------|----------------|
| **Permissions (authorities)** | *What kind of action?* e.g. `subscription.read`, `license.issue` | `users → user_roles → role_permissions → permissions`, resolved by `PermissionService` / `AuthoritiesLoader` into `GrantedAuthority`s. |
| **Org membership** | *Which tenant, and how senior?* `OWNER > ADMIN > MEMBER > VIEWER` | `org_members` rows, ranked by `TenantAccessChecker.rank()`. |
| **Super-admin** | *Global bypass?* | `users.super_admin` boolean. A super-admin's authorities are *all* permission codes plus `SUPER_ADMIN`. |

### 3.1 Authority resolution

`AuthoritiesLoader.authoritiesFor(userId, orgId, superAdmin)`:
- **super-admin** ⇒ every `Permission.code` in the catalog + `SUPER_ADMIN`.
- otherwise ⇒ `PermissionService.permissionsFor(userId, orgId)`, a JPQL join over
  `user_roles → role_permissions → permissions`. With `orgId == null` only *global*
  (org-scoped-null) role assignments count; with an org, both global and that-org
  assignments contribute.

`JwtAuthFilter` calls this with `orgId == null` (the session is org-agnostic), so the
authorities baked into the `SecurityContext` are the user's *global* grants. Per-resource
authorization is then done per-target-org by the tenant checker.

### 3.2 `TenantAccessChecker` — the composition point

Every resource-scoped `@PreAuthorize` delegates to the `@tenantAccess` bean. Its contract
is the single most important security invariant in the codebase, so it is worth stating
exactly. For *every* method the order is fixed and **default-deny**:

```
 canAccessOrg(orgId):                        canManageOrg(orgId):
   orgId == null              ─▶ false          orgId == null          ─▶ false
   no current user            ─▶ false          no current user        ─▶ false
   super_admin                ─▶ TRUE           super_admin            ─▶ TRUE
   api-key  ─▶ orgId == key's bound org         api-key                ─▶ FALSE (no write scope)
   human    ─▶ is an OrgMember of orgId         human  ─▶ rank(role) >= ADMIN in orgId
```

Resource-scoped checks **resolve the target's owning org first**, then defer to the two
primitives above:

```
 canReadSubscription(subId)        = resolveOrgForSubscription(subId).map(canAccessOrg)
 canWriteSubscription(subId)       = resolveOrgForSubscription(subId).map(canManageOrg)
 canIssueLicenseForSubscription    = canWriteSubscription
 canReadLicenseByJti(jti)          = jti → token → subscription → org → canAccessOrg
 canRevokeLicenseByJti(jti)        = ...                              → canManageOrg
```

**Why there is deliberately no global-authority short-circuit inside this bean.** It never
consults `subscription.read`, `license.issue`, etc. Those endpoint-level authority checks
stay in `@PreAuthorize` (AND/OR-composed there), but a *cross-org* bypass is impossible
because the checker ignores authorities entirely for resolution. The **only** global
bypass is `super_admin`. A null argument, a missing resource, or an unresolved org all
return `false`. This is what makes the family of "user with `subscription.read` reads
*another* org's subscription" IDOR bugs structurally impossible.

A concrete `@PreAuthorize` from `LicenseController`:

```java
@PostMapping("/subscriptions/{subId}/licenses")
@PreAuthorize("@tenantAccess.canIssueLicenseForSubscription(#subId)")  // resolves subId→org, needs ADMIN+
```

```java
@PostMapping("/licenses/{jti}/revoke")
@PreAuthorize("@tenantAccess.canRevokeLicenseByJti(#jti) or hasAuthority('SUPER_ADMIN')")
```

### 3.3 API-key principals are second-class on purpose

An `AuthenticatedUser` from `ApiKeyAuthFilter` has `userId == null`, `apiKey == true`, and
a bound `apiKeyOrgId`. The checker grants it **read access only to its bound org** and
**no write access by default** (`canManageOrg` returns false for keys). This caps the blast
radius of a leaked customer key.

---

## 4. Licensing + offline verification — end to end

This is the product. The flow spans both halves of the system and three signed artifacts.

### 4.1 Issue → sign → persist → download (the panel side)

```
 POST /api/v1/subscriptions/{subId}/licenses          (PreAuthorize: canIssueLicenseForSubscription)
   │
   ├─ LicenseIssuer.issue(subId, ttlOverride, audienceOverride, type)      @Transactional
   │    1. load subscription; reject unless status == ACTIVE
   │    2. LicenseClaimsBuilder.build(sub, ...):
   │         plan + org + RESOLVED entitlements (plan perms/features + subscription overrides)
   │         exp = min(ttlOverride|planDefault, subscription.endsAt)   (clamped to [1, 36500] days)
   │         claims: iss, aud, sub=orgId, jti=lic_<uuidhex>, iat, nbf, exp,
   │                 subscription_id, plan, permissions, features, seats, customer, version
   │    3. KeyService.getActiveSigningKeyPair()  (decrypts the active Ed25519 private key)
   │    4. JwsSigner.sign(claims, typ="license+jwt", activeKey)  ─▶ compact EdDSA JWS
   │    5. persist LicenseToken row (jti, kid, exp, fingerprint, status=ACTIVE, type)
   │    6. persist LicenseArtifact (the EXACT signed JWT) — SAME transaction
   │    7. AuditContext("license.issued")  +  outbox.publish("LicenseIssued")
   │
   └─ 201 { jti, kid, issuedAt, expiresAt, license:<jwt>, downloadUrl }
```

Two anti-patterns are explicitly designed out:
- **`GET /licenses/{jti}/download` is a pure read.** The signed artifact is persisted at
  issue time (`LicenseArtifact`), so download just returns the stored bytes (or 404 if
  unknown, **410 Gone** if revoked). It never calls `issuer.issue()` — closing the path
  (audit P1-4) where a read-only principal could mint a *brand-new* license (new jti, new
  outbox event, zero audit) by downloading a jti that aged out of a cache.
- **The artifact is written in the same transaction as the token row**, so an issued jti
  *always* has a downloadable artifact — no race where the row exists but the bytes don't.

The download wraps the JWT in the `.lic` envelope (`LicenseFileBuilder`):

```json
{ "license": "<compact EdDSA JWS>", "issued_at": "...", "customer": "Acme",
  "plan": "pro", "expires_at": "...", "notes": "Drop this file at /etc/app/license.lic." }
```

### 4.2 The JWKS — the trust root for offline verification

`GET /.well-known/jwks.json` (public) publishes the **ACTIVE + recently-RETIRED** signing
public keys as Ed25519 OKP JWKs. `KeyService.listPublishedKeys()` returns ACTIVE plus
RETIRED-within-18-months, and **excludes COMPROMISED keys** — so flagging a key compromised
drops it from the JWKS immediately and verifiers stop trusting its tokens at their next
refresh. RETIRED keys stay published during retention so already-issued, still-valid
licenses signed by an old key keep verifying across a rotation.

### 4.3 Customer verification (the SDK side — `license-verifier`)

The customer app holds the JWKS (from classpath `/jwks.json` or refreshed from a URL) and
the `.lic` file. `LicenseVerifier.verify(content)` does, in order:

```
 1. extract the JWT (unwrap the {"license": ...} envelope if present)
 2. parse the JWS; require header alg == EdDSA
 3. require typ == "license+jwt"  (a MISSING typ is rejected too: an attacker who can mint
       a CRL or session token signed by the same key must not strip typ and pass it as a license)
 4. require a non-blank kid; resolve the verifier for that kid from the JWKS (Ed25519Verifiers)
 5. verify the Ed25519 signature
 6. temporal: exp is MANDATORY (a license with no exp is malformed, not "expired");
       reject if now > exp + clockSkew;  reject if now < nbf - clockSkew
 7. audience must contain the configured audience
 8. issuer must match (if configured)
 9. REVOCATION: checkRevocation(jti)
 10. map claims ─▶ immutable License (permissions, features, seats, customer, ...)
```

Step 9 is **fail-closed**: if the `RevocationChecker` is *not operational* (e.g. its cached
CRL is stale or never loaded), `verify` throws `LicenseRevokedException` for *every*
license — a stale revocation view denies access rather than silently trusting it.

`verifyAllowingExpired` is the one relaxation: it enforces everything *except* a
present-but-past `exp`, used by the starter's READ_ONLY-on-expiry grace so a container
restart after expiry can still boot (a missing `exp` is still rejected).

### 4.4 Revocation propagation via the signed CRL

Revocation can't be a panel lookup (the customer app is offline-capable), so it propagates
through a **signed, cacheable CRL**:

```
 PANEL                                         CUSTOMER APP (starter: CrlRevocationChecker)
 ─────                                         ──────────────────────────────────────────
 POST /licenses/{jti}/revoke                   @Scheduled every app.license.crl-refresh-interval (15m)
   └─ LicenseRevocationService.markRevoked       └─ GET app.license.crl-url
        guarded conditional UPDATE                    └─ CrlVerifier.verify(jws):
        (revokeIfNotRevoked) — atomic vs a                 typ==crl+jwt, EdDSA, known kid, sig,
        concurrent heartbeat; idempotent                   issuer match ─▶ RevocationList
        outbox.publish("LicenseRevoked")              ── monotonicity guard: reject a validly-signed
                                                          but OLDER CRL (rollback / replay defense) ──
 GET /licenses/crl  (public, CACHED)                  current.set(newList)
   └─ CrlController.crl():
        stateKey = <count>:<maxRevokedAtMillis>     RevocationChecker.isRevoked(jti):
        re-sign ONLY when stateKey changed OR          list == null            ─▶ true (never loaded)
        cached JWS older than crl-ttl (1h)             list stale beyond max-stale ─▶ true (fail closed)
        prune expired-but-revoked jtis (P3)            else                    ─▶ list.contains(jti)
        claims: iss, iat, nextUpdate, revoked[]
        sign with active key, typ=crl+jwt
```

Reliability details worth internalizing:
- **The CRL endpoint is cached** (audit P2). It is `permitAll`, and previously re-scanned
  the revoked set, decrypted the signing key, and Ed25519-signed on *every* anonymous hit —
  a DoS amplifier. Now it caches the signed JWS in an `AtomicReference` and only re-signs
  when the cheap `revocationStateKey` changes or the TTL lapses. Readers swap an immutable
  snapshot atomically.
- **The CRL is bounded.** It only carries REVOKED-and-still-unexpired jtis; an offline
  verifier already rejects expired ones on `exp`, so the list cannot grow without bound.
- **Rollback is rejected on the consumer.** `CrlRevocationChecker.isRollback` refuses a
  validly-signed but older CRL, so a MITM/stale-mirror cannot replay a previous CRL that
  omits a recently-revoked jti.
- **Both layers fail closed.** Inside the SDK (`LicenseVerifier.checkRevocation`) *and* in
  the starter's status mapping (`LicenseService.status()` returns `REVOKED` when the checker
  is non-operational), a stale/missing CRL denies access.
- **Subscription lifecycle cascades.** Suspending/cancelling a subscription calls
  `revokeAllActiveForSubscription`, so the now-invalid offline licenses reach the CRL
  instead of staying valid (and seat-holding) until natural expiry (audit P1-5).

### 4.5 Enforcement in the customer app (the starter)

`license-verifier-spring-boot-starter` auto-wires (`LicenseVerifierAutoConfiguration`) one
shared `PublicKeyProvider` (so verifier + CRL checker use the *same* keys and refresh
thread), the `RevocationChecker`, the `LicenseVerifier`, a `LicenseService` (in-memory
holder, reloaded on a schedule), and a `RequiresPermissionAspect`. Application code guards
methods declaratively:

```java
@RequiresPermission("reports.export")   // or anyOf={...}, readOnly=true
public byte[] exportReport() { ... }
```

The aspect maps the license's *current* `Status` to access:

```
 NOT_LOADED | EXPIRED | REVOKED ─▶ deny (LicensePermissionDeniedException)
 READ_ONLY  + !rp.readOnly()    ─▶ deny (mutating op rejected during the grace window)
 ACTIVE/READ_ONLY + permission present ─▶ proceed
```

`status()` evaluates **revocation first** (a license revoked *after* load, or a stale CRL,
denies everything regardless of `exp`), then expiry → READ_ONLY/EXPIRED, else ACTIVE.

---

## 5. The transactional outbox → webhook fan-out reliability model

Domain events must be delivered *exactly when and only when* the business transaction that
produced them commits. The classic dual-write hazard (write the row, then call the webhook,
and crash in between) is avoided with a **transactional outbox**.

### 5.1 Enqueue (atomic with the business write)

`OutboxPublisher.publish(aggregateType, aggregateId, eventType, payload)` simply
`INSERT`s a row into `outbox_events` using the *same* JDBC connection/transaction as the
calling service (`LicenseIssuer`, `LicenseRevocationService`, `KeyService`, etc.). So the
event row and the `license_token`/`signing_key` row commit or roll back together. There is
no second system to coordinate with at write time.

### 5.2 Two independent consumers of `outbox_events`

The same outbox table is drained by **two** schedulers that never contend, because they
key off *different* columns:

```
                          ┌──────────────────────────────┐
                          │        outbox_events          │
                          │  status (PENDING/PUBLISHED/   │  ◀── OutboxDeliveryScheduler
                          │          FAILED)              │      (NOTIFY consumer; status machine)
                          │  fanned_out_at (NULL / ts)    │  ◀── WebhookDispatchScheduler
                          └──────────────────────────────┘      (webhook fan-out; durable marker)
```

**OutboxDeliveryScheduler** (`pg_notify` publisher), every 5s:
- Claims a batch of due `PENDING` rows with `SELECT ... FOR UPDATE SKIP LOCKED` ordered by
  `occurred_at`. The row locks are held for the transaction, so a sibling instance skips
  what this one grabbed — **no double-publish, no blocking**.
- Calls *parameterized* `SELECT pg_notify(channel, payload)`. Because `NOTIFY` is buffered
  until commit, the `status='PUBLISHED'` update and the notification are atomic.
- Per-row failure handling is isolated (one bad row never poisons the batch): increment
  `attempts`, record a truncated `last_error`, schedule `next_attempt_at` with capped
  exponential backoff (`5s * 2^(n-1)`, cap 1h), and after `MAX_ATTEMPTS=10` quarantine the
  row as `FAILED`.

**WebhookDispatchScheduler** (per-tenant HTTP fan-out), every 5s, in two phases:

```
 Phase 1 — FAN-OUT (synchronous, on the @Scheduled thread; bounded + fast)
   claim FANOUT_BATCH=500 events WHERE fanned_out_at IS NULL  (FOR UPDATE SKIP LOCKED)
   for each: resolve owning org (aggregate → subscription → org, or payload org_id)
             insert a PENDING webhook_deliveries row per matching active subscription
             (UNIQUE(subscription_id, event_id) + ON CONFLICT DO NOTHING — idempotent)
             ALWAYS stamp fanned_out_at  (means "considered", not "produced a delivery")

 Phase 2 — DELIVER (off-thread, dedicated single-thread executor; tarpit-prone)
   claim DELIVER_BATCH=100 due PENDING deliveries (FOR UPDATE OF d SKIP LOCKED)
   per row: build the signed body, DNS-rebind-safe re-resolve+pin the URL (SSRF guard),
            HMAC-SHA256 sign, POST.  2xx ─▶ DELIVERED; else ─▶ backoff/quarantine (max-attempts=8)
```

Why this shape:
- **Durable claim, not a time window.** `fanned_out_at` is a *per-event marker*, so an
  event can never be silently dropped by ageing out of a lookback window — it stays
  claimable until a fan-out tick commits. At-least-once delivery; nothing is lost if a tick
  crashes.
- **Phase isolation prevents starvation (P1-11).** Fan-out runs synchronously (fast);
  delivery runs on its own daemon executor with an `AtomicBoolean` in-flight guard. A
  tarpit endpoint that stalls a delivery batch can *never* stretch the gap between fan-out
  ticks, so one hung tenant cannot drop events for others.
- **Self-proxy for transactions (P2).** `dispatch()` calls `self.fanOut()` /
  `self.deliverDueBatch()` through the Spring proxy. A direct self-invocation would bypass
  the proxy and run the `FOR UPDATE SKIP LOCKED` claim in autocommit, releasing locks
  immediately and allowing duplicate deliveries across instances.
- **Signed deliveries.** Each POST carries `X-CP-Event`, `X-CP-Delivery` (stable across
  retries — a natural idempotency key for the receiver), `X-CP-Timestamp`, and
  `X-CP-Signature = "sha256=" + HMAC_SHA256(secret, "<timestamp>.<body>")`. The secret is
  KEK-encrypted at rest and decrypted per attempt.
- **SSRF + DNS-rebind defense.** The destination URL is validated at registration *and*
  re-resolved/IP-pinned immediately before each send (the JDK client would otherwise
  re-resolve at send time, letting an org admin repoint DNS at an internal address).

### 5.3 Retention without data loss

A separate hourly sweep in `OutboxDeliveryScheduler` purges terminal
(`PUBLISHED`/`FAILED`) outbox rows older than `app.outbox.retention` (30d) — but **only
rows the webhook fan-out has already considered** (`fanned_out_at IS NOT NULL`) and never
`PENDING` rows. So retention can't race the at-least-once webhook fan-out and lose an
event. Webhook delivery rows have their own 30-day terminal-row sweep.

---

## 6. Crypto & key management

Two distinct cryptosystems, plus an envelope scheme protecting all secrets at rest.

### 6.1 Ed25519 signing keys (license + CRL)

`SigningKey` rows hold a `kid`, the public key as PEM, the **KEK-encrypted private key**,
a status (`ACTIVE` / `RETIRED` / `COMPROMISED`), and timestamps. `KeyService` owns the
lifecycle:

- **Bootstrap.** On `ApplicationReadyEvent`, if no ACTIVE key exists, generate one. Routed
  through a self-proxy (`self.generateNewActiveKey()`) so it runs in its own transaction;
  a concurrent bootstrap on another instance that loses the partial-unique-index race is
  treated as success (exactly one ACTIVE key is the goal).
- **Rotation.** `generateNewActiveKey()` retires existing ACTIVE keys, **flushes the
  retire UPDATEs before** inserting the new ACTIVE row — because Hibernate orders INSERTs
  before UPDATEs in a flush, and the partial unique index
  `ux_signing_keys_single_active` would otherwise be violated mid-transaction. The retired
  key stays published in the JWKS during retention so its still-valid licenses verify.
- **Compromise.** `markCompromised(kid)` flips status to COMPROMISED (dropped from JWKS
  immediately), and if the compromised key was ACTIVE, mints a fresh ACTIVE key so signing
  never gaps. Idempotent on a repeat call.
- **Signing.** `JwsSigner` extracts the raw 32-byte Ed25519 key material from the JCA
  PKCS8/SPKI encodings, builds a Nimbus `OctetKeyPair`, and produces a compact EdDSA JWS
  whose header carries `kid` + the requested `typ` (`license+jwt` or `crl+jwt`). Shared by
  both license issuance and CRL signing so they never diverge.

### 6.2 The AES-GCM KEK envelope (secrets at rest)

`KeyEncryptor` protects **four** categories of secret under one rotatable
**key-encryption-key (KEK)**: signing private keys, TOTP secrets, webhook HMAC secrets,
and OIDC client secrets. Each ciphertext is a **versioned envelope**:

```
 [0x01 magic][1-byte idLen][KEK-id UTF-8][IV (12B)][ciphertext || GCM tag(16B)]
 legacy (pre-versioning):   [IV (12B)][ciphertext || GCM tag]    decrypted under KEK id "default"
```

KEKs are configured as `app.signing.master-keys` (`id:base64,...`);
`active-master-key-id` selects which encrypts *new* blobs; *every* listed id can still
*decrypt* old blobs. `decrypt` tries a versioned parse first and falls back to the legacy
layout under the default KEK — and because the GCM tag authenticates, a wrong
interpretation fails authentication rather than returning garbage.

### 6.3 KEK rotation — kept in lock-step

The hazard with a single KEK protecting four tables: rotate only `signing_keys` and drop
the old KEK, and you permanently orphan the MFA/webhook/SSO secrets. Two mechanisms keep
the four in lock-step, driven by a single registry, `KeyService.ENCRYPTED_COLUMNS`:

```
 KeyService.rotateKek()         ── one transaction ──
   for each (table, pk, blobCol) in ENCRYPTED_COLUMNS:
       SELECT pk, blob WHERE blob NOT NULL
       for each row: blob' = encrypt(decrypt(blob))   ── re-wrap under the ACTIVE KEK
       UPDATE table SET blobCol = blob' WHERE pk = ?   ── targeted single-row update via native SQL
   (cross-package columns touched GENERICALLY — no compile-time dep on mfa/webhooks/sso)

 KeyService.assertNoOrphanedKekReferences()  @EventListener(ApplicationReadyEvent, Order 100)
   scan every ENCRYPTED_COLUMNS blob's referenced KEK id; if any references a KEK that is
   no longer configured ─▶ FAIL FAST at startup (rather than throwing later at MFA login /
   webhook delivery / SSO).  Runs AFTER bootstrap so the fresh bootstrap key is included.
```

So the operational rule is: add the new KEK, point `active-master-key-id` at it, run
`rotateKek`, *then* remove the old KEK — and the startup drop-guard enforces that you can't
remove it too early.

---

## 7. Idempotency, observability, graceful shutdown

### 7.1 Idempotency-Key

`IdempotencyInterceptor` (+ `IdempotencyConfig` body-caching filter) gives mutating
endpoints safe client retries:

```
 preHandle (mutating + non-blank Idempotency-Key):
   natural key = (idemKey, method, path, ACTOR)        actor = userId | apikey:<org> | anonymous
   requestHash = sha256(body)
   live record found?
     completed + same hash ─▶ REPLAY stored status+body+headers (Idempotency-Replayed: true)
     completed + diff hash ─▶ 422 (key reused for a different request)
     in-flight             ─▶ 409 (original still running / crashed)
     none                  ─▶ INSERT in-flight row (UNIQUE makes a concurrent dup lose the race)
 afterCompletion:
   2xx/3xx  ─▶ complete() — persist status+body+Content-Type+Location (replayable)
   4xx/5xx / threw ─▶ delete the claim (transient/correctable; a later retry re-executes)
```

`Store` runs each persistence method in its own `REQUIRES_NEW` transaction, so claiming/
completing a key commits independently of the (possibly rolling-back) business handler.
The actor is part of the key so callers can't replay each other's responses. Only success
is cached — caching a 4xx would pin a correctable failure for the whole TTL.

### 7.2 Observability

- **Correlation id** (`CorrelationIdFilter`) binds `requestId` to MDC for the whole
  request; surfaced by `logback-spring.xml` (plain in dev, JSON field in prod) so every log
  line — DB, outbox, NOTIFY — correlates.
- **Access log** (`AccessLogFilter`): one structured line per request (method/path/status/
  latency/client-IP), health probes excluded, gated by `app.observability.access-log`.
- **Metrics** (`ObservabilityConfig`): a common `app=control-panel` tag on every meter,
  `@Timed` support, and a `MetricsService` facade owning canonical names
  (`cp.outbox.failed`, `cp.webhook.attempts`, `cp.licenses.issued/revoked`,
  `cp.auth.login.failures/lockouts`, `cp.redis.fallbacks`, `cp.outbox.backlog`). It falls
  back to a no-op registry so slice tests don't NPE.
- **Audit** (`AuditInterceptor` + `AuditWriter` + `AuditContext`): an AspectJ advice on
  mutating controller methods writes one canonical row (actor/org/action/target/payload/ip/
  outcome). High-value actions (`license.issued`, `key.rotated`, `rbac.role.*`, ...) are
  **fail-closed** — the audit INSERT runs *inline* in the business transaction, so if it
  can't be written the business action rolls back. Everything else is fail-open
  (`REQUIRES_NEW`, best-effort, survives a business rollback). A `recorded` sentinel on the
  thread-local `AuditContext` stops the aspect, the global handler, and explicit writes from
  duplicating rows; `AuditContext.clear()` is called in `finally` everywhere so the
  thread-local can't leak across pooled requests.
- **Actuator surface is minimized**: only `health`, `info`, `prometheus` are web-exposed,
  and `SecurityConfig` restricts `/actuator/**` (beyond health/info) to `SUPER_ADMIN`.

### 7.3 Trusted-proxy client-IP resolution

`TrustedProxyResolver` gives *one* non-spoofable client-IP source used by the rate limiter,
the auth filter, the audit writer, and the access log. It honors the **leftmost**
`X-Forwarded-For` token **only** when the direct socket peer is inside a configured
`app.audit.trusted-proxies` CIDR; otherwise it returns the raw peer. Paired with
`server.forward-headers-strategy=framework` so HSTS and scheme-derived URLs are correct
behind a TLS-terminating proxy. (Operational requirement: the proxy must set XFF/XFP and
strip client-supplied copies.)

### 7.4 Graceful shutdown

`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`: on SIGTERM
the container stops accepting new requests and drains in-flight ones (bounded by the
timeout) instead of cutting connections. This matters specifically for the schedulers —
the outbox/webhook claim transactions hold `FOR UPDATE SKIP LOCKED` row locks, and a clean
drain lets the in-flight batch commit (or roll back) rather than leaving rows locked until
the connection dies. The webhook delivery executor is shut down via `@PreDestroy`.

---

## 8. How it all fits the bigger picture

Read top to bottom, the system is a series of **trust-narrowing boundaries**, each with an
explicit fail posture:

1. **The edge** rejects what it can cheaply (size, rate) before spending CPU.
2. **Authentication** establishes *who*, always against live DB state (never a frozen
   claim), with three revocation channels that mostly fail *closed*.
3. **Authorization** narrows to *which tenant* through a single composition point
   (`TenantAccessChecker`) whose only global bypass is super-admin — making cross-tenant
   IDOR structurally impossible rather than convention-dependent.
4. **The product** (licensing) pushes trust *out* of the panel entirely: a signed JWT + a
   published JWKS + a signed CRL let a disconnected customer app verify and enforce
   licenses with zero calls home, while still honoring revocation — and every consumer
   fails closed on a stale revocation view.
5. **Reliability** (outbox + webhooks) guarantees events are emitted exactly with their
   business transaction and delivered at-least-once, with poison quarantine, phase
   isolation, and SSRF defense.
6. **Crypto** protects the signing keys that anchor (4) and keeps four categories of
   at-rest secret rotatable in lock-step, refusing to start if a rotation would orphan any.
7. **Cross-cutting concerns** (idempotency, correlation, audit, metrics, graceful
   shutdown) make the whole thing safe to retry, observable end-to-end, and clean to
   restart.

The recurring design signatures to recognize when reading any file in the codebase:
**default-deny**, **fail-closed on the security-critical paths**, **resolve fresh state
rather than trust a token claim**, **claim work durably with `FOR UPDATE SKIP LOCKED`**,
**self-proxy so `@Transactional`/`REQUIRES_NEW` actually applies**, and **never leak
internals in an error body**.
