# Security Tickets — LOW and INFO Severity

Extracted from the security audit findings (`AUDIT_findings.txt`). Each ticket corresponds to one finding of severity LOW or INFO. The ticket id `SEC-NNN` uses the original finding ordinal (zero-padded). Technical detail is preserved faithfully from the audit; no findings were invented.

Total tickets: 25 (24 LOW, 1 INFO).

---

## [SEC-053] SSO (OAuth2/SAML) login uses HTTP session and redirect flow under a STATELESS, CSRF-disabled chain

- **Severity:** LOW (audit verdict: uncertain; reviewer corrected severity from medium down to low)
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java` — `securityFilterChain`: `.csrf(csrf -> csrf.disable())` (line 63), `SessionCreationPolicy.STATELESS` (line 65), `http.oauth2Login(...)` / `http.saml2Login(...)` (lines 99-100); `SsoSuccessHandler` reads `request.getSession().getAttribute("sso.orgId")` (`SsoSuccessHandler.java` line 109).

**Description:**
The single security chain disables CSRF and forces STATELESS session creation, which is correct for the Bearer/ApiKey header-token API (JwtAuthFilter and ApiKeyAuthFilter both read only the Authorization header — no cookie auth — so CSRF on the API itself is not a concern). However, the same chain also enables OAuth2 and SAML login. Those flows are inherently session/cookie based: Spring Security stores the OAuth2 `state`/authorization-request and the SAML relay-state/`InResponseTo` in the HTTP session, and SsoSuccessHandler explicitly relies on a session attribute (`sso.orgId`). With `SessionCreationPolicy.STATELESS`, Spring will not persist those values via its own SecurityContextRepository, and mixing a STATELESS, CSRF-disabled policy with session-dependent login filters in one chain is a misconfiguration.

Verifier calibration: The structural facts are accurate — there is exactly one SecurityFilterChain (SsoSecurityConfig defines only ClientRegistration/RelyingParty repositories, not a chain), and that single chain combines `csrf.disable()` + STATELESS with `oauth2Login` + `saml2Login`, while SsoSuccessHandler reads a session attribute. The real, demonstrable defect is functional: `sso.orgId` is read but never written, so `extractOrgId()` always returns null and SSO JIT users are never bound to their org (broken org auto-membership), and STATELESS makes session reliance fragile/undefined across servlet containers. The originally-claimed security bypass (STATELESS "silently bypasses" OAuth2 state / SAML InResponseTo, enabling login-CSRF or SAML assertion replay) is NOT substantiated: STATELESS nulls Spring's SecurityContextRepository but does not stop `HttpSessionOAuth2AuthorizationRequestRepository` / `HttpSessionSaml2AuthenticationRequestRepository` from creating a container JSESSIONID, so state/AuthnRequest can still round-trip and Spring fails closed (`authorization_request_not_found` / InResponseTo rejection) when it cannot. Hence severity corrected to low.

**Attack scenario:**
(As originally framed, NOT supported by the code per the verifier:) Because the authorization-request/state cannot be reliably round-tripped via session under STATELESS, the OAuth2 login callback `/login/oauth2/code/**` may accept a forged or replayed authorization response (login CSRF), letting an attacker complete an SSO login binding the victim's browser to an attacker-controlled identity, or replay a captured SAML assertion that the InResponseTo/session check would otherwise reject. The verifier could not demonstrate this exploit from the code; the realistic impact is flaky/undefined SSO behavior across servlet containers plus broken org auto-membership.

**Recommended fix:**
Split configuration into two SecurityFilterChain beans ordered by `@Order`: (1) a stateful chain scoped to the SSO/login paths (`/login/**`, `/oauth2/**`, `/saml2/**`) with default CSRF and `SessionCreationPolicy.IF_REQUIRED` so the OAuth2/SAML state and relay-state are preserved; (2) the existing STATELESS, CSRF-disabled, Bearer-token chain scoped to `/api/**`. Do not enable `oauth2Login`/`saml2Login` inside the STATELESS chain. Additionally fix the functional bug: actually set `sso.orgId`, or carry orgId via OAuth2 `state` / SAML `RelayState` instead of the session.

- [ ] Status: open

---

## [SEC-054] Exception handlers echo raw exception messages to clients (IllegalArgument / AccessDenied / Authentication)

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/common/GlobalExceptionHandler.java` — `handleIllegal` (lines 59-64, `pd ... ex.getMessage()`), `handleAccessDenied` (lines 32-37, `ex.getMessage()`), `handleAuth` (lines 39-44, `ex.getMessage()`).

**Description:**
The catch-all `handleGeneric(Exception)` correctly returns an opaque "An unexpected error occurred" with no stack trace. However, `handleIllegal(IllegalArgumentException)` places `ex.getMessage()` directly into the ProblemDetail detail returned to the client, as do `handleAccessDenied` and `handleAuth`. IllegalArgumentException is thrown widely by library/JDK code (e.g. `UUID.fromString`, enum parsing, validation internals) with messages that can reflect attacker input or internal state, and these are surfaced verbatim. This is information disclosure (and a potential reflected-content vector), inconsistent with the otherwise-sanitized generic handler.

Verifier calibration: A concrete unguarded path reaches `handleIllegal` with attacker-influenced, internally-revealing content: in `LicenseController.list` (lines 108-117) the attacker-controlled `@RequestParam String status` is passed directly to `LicenseToken.Status.valueOf(status.toUpperCase())` with NO try/catch. An invalid value makes `Enum.valueOf` throw `IllegalArgumentException("No enum constant com.example.cp.licenses.LicenseToken.Status.<UPPERCASED-INPUT>")`, surfaced verbatim, disclosing the fully-qualified internal class name and reflecting the attacker's input. The inconsistency is demonstrable: `OrgController.addMember` (lines 70-74) and `SubscriptionService.persistOverride` (lines 207-211) both catch the same exception and substitute a safe generic message. The `handleAccessDenied`/`handleAuth` portion is weaker: with `@EnableMethodSecurity`, `@PreAuthorize` denials produce framework-constant messages ("Access is denied"), not attacker input, and the JWT auth-failure path bypasses `handleAuth` entirely via `JwtAuthFilter.writeProblem` (lines 89-94) — so that part is essentially cosmetic. Net: confirmed at low.

**Attack scenario:**
An attacker submits malformed path/query values that trigger an IllegalArgumentException deep in a library; the raw message (potentially including class names, expected formats, or fragments of internal data) is returned in the 400 ProblemDetail, aiding reconnaissance of internal types and validation logic.

**Recommended fix:**
Return a generic, non-reflective detail for IllegalArgumentException (e.g. "Invalid request") and log the real message server-side with a correlation id. For AccessDenied/Authentication, return fixed "Forbidden"/"Unauthorized" details rather than the framework message. Reserve attacker-meaningful detail for the explicit ApiException path where messages are author-controlled.

- [ ] Status: open

---

## [SEC-055] CORS allows credentials with a single hardcoded dev origin shipped to production

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** api-hardening
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java` — `corsConfigurationSource` (lines 106-118): `setAllowedOrigins(List.of("http://localhost:5173"))` (line 109), `setAllowCredentials(true)` (line 113), registered for `/**` (line 116).

**Description:**
CORS is configured with `allowCredentials(true)` and a single, hardcoded `http://localhost:5173` origin registered for `/**`. This is NOT the dangerous wildcard-with-credentials anti-pattern (Spring rejects `*` + credentials, and only the exact dev origin is allowed), so it is not exploitable as-is. The concern is operational/hardening: the production origin is hardcoded to a localhost dev URL rather than being driven by configuration (everything else uses `${...}` env placeholders, e.g. `app.ui.base-url`). The project already exposes a config-driven UI origin — `application.yml` line 49 defines `app.ui.base-url: ${APP_UI_BASE_URL:http://localhost:5173}`, already consumed via `@Value` in `SsoSuccessHandler.java` (line 33) and `SsoLoginController.java` (line 22) — but the CORS bean ignores it and hardcodes the literal localhost string. In production this either breaks the real UI (forcing an emergency widening of the origin list) or someone hardcodes a permissive value. The `*` exposed-headers/methods themselves are fine.

**Attack scenario:**
Low direct risk today. The realistic failure mode: under deployment pressure the allowed origin gets broadened (e.g. to a wildcard subdomain or reflected origin) to make the real UI work, and because `allowCredentials` is true that change immediately enables cross-origin credentialed reads of the authenticated API from attacker-controlled sites.

**Recommended fix:**
Drive allowed origins from configuration (e.g. bind `app.ui.base-url` / a dedicated CORS-origins property) instead of hardcoding localhost, and keep the list to the exact production UI origin(s). Document that `allowCredentials(true)` must never be combined with a reflected or wildcard origin.

- [ ] Status: open

---

## [SEC-056] Public key prefix is derived from the secret itself and leaked to DB/audit/UI in plaintext

- **Severity:** LOW (audit verdict: confirmed; reviewer downgraded from medium to low)
- **Dimension:** apikey-security
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyService.java` — `create()` lines 41-43, 55, 63; `verify()` line 70 (lookup via `findByKeyPrefix`, repository line 15, used at `verify()` line 72); `ApiKey.keyPrefix` column (`ApiKey.java` lines 40-41).

**Description:**
The plaintext key is built as `"cp_" + base32(32 random bytes)`, and the stored/loggable prefix is taken as `plaintext.substring(0,8)` — i.e. `cp_` plus the FIRST 5 base32 characters of the secret. Base32 encodes 5 bits/char, so the prefix is `cp_` plus exactly ~25 bits of the actual secret material. This prefix is (a) stored in cleartext in `api_keys.key_prefix` (non-nullable column), (b) written into the audit payload via `AuditContext.putPayload("prefix", prefix)` at line 63, and (c) returned to and displayed by the admin UI (via `CreateResponse.keyPrefix` at `ApiKeyController` line 58 and `ApiKeyDto.keyPrefix` at line 64, the latter returned by the org-member-readable GET list endpoint at line 41). Crucially, the verification hash is computed over the FULL plaintext including those prefix chars (line 43, `sha256Hex(plaintext)`), so the leaked bytes are genuinely part of the verification secret rather than a separate opaque identifier — the core design flaw. A non-secret prefix should be an opaque identifier that is NOT part of the verification secret.

Verifier calibration: The design issue is real and confirmed, but the severity framing is overstated. The secret is 256 bits of entropy; leaking the first 25 bits leaves 231 bits unknown — far beyond any feasible brute-force/offline-cracking capability, so the "reduces brute-force space / enables offline cracking" scenario is not practically exploitable. It is defense-in-depth erosion and a partial-secret-disclosure hygiene smell, not a path to actual key recovery. Downgraded from medium to low.

**Attack scenario:**
An attacker who obtains read access to the audit log table, application logs, or a DB backup (or a low-privilege org member who can call `GET .../api-keys`) learns `cp_` + 5 leading secret characters for every key. Combined with any side channel that narrows the rest, or simply as defense-in-depth erosion, the persisted prefix shrinks the unknown portion of the secret and gives an attacker a confirmed lookup selector (`findByKeyPrefix`) to target offline cracking of the remaining bytes.

**Recommended fix:**
Decouple the public prefix from the secret. Generate a separate non-secret key identifier (e.g. `cp_<random keyId>_<secret>`) and store/log/display only the keyId portion, never any byte of the secret. Look up by the dedicated keyId column. If a human-readable prefix of the secret must be kept, keep at most a 2-3 char masked tag and never persist contiguous leading secret bytes.

- [ ] Status: open

---

## [SEC-057] Authentication path performs a DB write (lastUsedAt) on every request inside a read-write transaction

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** apikey-security
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyService.java` — `verify()` lines 67-82 (`@Transactional`, `k.setLastUsedAt(...)` + `repo.save(k)` on every successful auth, lines 76-77); called from `ApiKeyAuthFilter.doFilterInternal` (line 43).

**Description:**
`verify()` is annotated `@Transactional` (read-write; not `readOnly=true`, unlike `listForOrg` at line 95) and, on every successful authentication, calls `k.setLastUsedAt(OffsetDateTime.now())` and `repo.save(k)`, issuing an UPDATE to `api_keys` per request. This turns the hot auth path into a write path: high request volume on a single key causes continuous row updates (MVCC dead-tuple/bloat on the hot row, per-row write-lock serialization, WAL/IO amplification, and contention with `revoke()` at lines 84-93 which writes the same row in its own transaction). It also means a burst of authenticated traffic from one key can degrade auth latency.

Verifier calibration: Two caveats reduce weight: (1) the `ApiKey` entity has NO `@Version` field, so the originally-cited "row-version churn / optimistic-lock" detail is inaccurate for this codebase; (2) under PostgreSQL MVCC, readers (the SELECT in `findByKeyPrefix`) are not blocked by these writes, and only concurrent writers to the same row serialize, so the "degrade auth latency for everyone / starve revoke" framing is somewhat overstated for typical loads. Net: a valid low-severity, non-security (performance/operational) finding — not an auth bypass, key disclosure, or privilege issue.

**Attack scenario:**
A high-throughput (or malicious) client repeatedly authenticates with a valid key; each request updates the same `api_keys` row, producing lock contention and write amplification that slows the authentication filter, and can contend with legitimate revoke operations on the same row.

**Recommended fix:**
Make `verify()` read-only and update `last_used_at` out of band: throttle the write (e.g. only update if `last_used_at` is older than N minutes), perform it asynchronously, or batch it. At minimum split the read (auth decision) from the write so authentication does not depend on a successful UPDATE.

- [ ] Status: open

---

## [SEC-058] occurred_at and login IP are recorded from spoofable application-side sources

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** audit-integrity
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/audit/AuditWriter.java` — `occurred_at` set via `OffsetDateTime.now()` (bound as 9th VALUES parameter, INSERT at lines 47-48, `ps.setObject(9, ...)` line 58); IP via `AuditInterceptor.extractIp` trusting `X-Forwarded-For` (lines 107-115; fallback invoked at `afterMutating` line 59); `JwtAuthFilter.setIp` uses `getRemoteAddr` (line 80). Schema: `08-audit.sql` line 13 (column DEFAULT `now()`).

**Description:**
Two integrity weaknesses in captured metadata. (1) `occurred_at` is written from the application JVM clock (`OffsetDateTime.now()`, line 58) rather than the database DEFAULT `now()` defined in `08-audit.sql` line 13, so the authoritative timestamp depends on app-host clock correctness/skew rather than a single trusted DB clock. (2) For authenticated requests the IP is reliably set by `JwtAuthFilter` from `request.getRemoteAddr()` (line 80), but for unauthenticated mutating endpoints — most importantly `POST /api/v1/auth/login`, which has no Bearer token so `JwtAuthFilter` returns early (lines 45-49) and never sets `AuditContext` IP — the interceptor falls back to `extractIp(req)`, which blindly trusts the first comma-separated entry of the client-supplied `X-Forwarded-For` header (lines 109-113) with no validation that the request actually came through a trusted proxy. No mitigating config exists: `application.yml` has no `server.forward-headers-strategy`, and a repo-wide search found no `ForwardedHeaderFilter`/`RemoteIpValve`/trusted-proxy config in production code. Login is audited (`AuditContext.set("auth.login")` at `AuthController.java:112`), so the IP recorded for this security-relevant unauthenticated event is attacker-controlled. (Note: login FAILURES throw `ApiException` and the `@AfterReturning` advice fires only on normal return, so only successful logins are currently audited via this path.)

**Attack scenario:**
An attacker brute-forcing or successfully logging in sends `X-Forwarded-For: 10.0.0.5` (an internal/innocent address). For the recorded successful login (or any future failed-login auditing), the IP attribution points investigators at the spoofed address rather than the attacker's real source.

**Recommended fix:**
Use the database clock for `occurred_at` (omit the column on INSERT and rely on DEFAULT `now()`, or set via SQL `now()`). Only honor `X-Forwarded-For` when the immediate peer is a configured trusted proxy (Spring `ForwardedHeaderFilter` / trusted-proxy allowlist), otherwise record `getRemoteAddr()`; never trust raw XFF for audit attribution of unauthenticated endpoints.

- [ ] Status: open

---

## [SEC-059] No audit-log retention or lifecycle control; orphaned admin.audit.export permission with no enforcing endpoint

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** audit-integrity
- **File(s)/location:** `control-panel-api/src/main/resources/db/changelog/changes/08-audit.sql` — `audit_log` table + immutability triggers (whole file); permission seed `admin.audit.export` in `03-plans.sql` line 65.

**Description:**
There is no retention policy and no controlled deletion/archival path for `audit_log`: no `@Scheduled` cleanup (the only scheduled task is `OutboxPublisher.java:31`), no partitioning, and the `BEFORE DELETE` trigger (`audit_log_no_delete` → `audit_log_block_modifications()`, which unconditionally `RAISE EXCEPTION`) blocks ALL deletes. So the table grows unbounded and the only way to ever prune or archive is to disable/drop the immutability trigger (which is itself unaudited — see the trigger-bypass finding). This is an availability/operability gap and means any legitimate retention/erasure (e.g. erasure of an actor's email surfaced in the UI) cannot be performed through a controlled, audited mechanism. Separately, a permission `admin.audit.export` is seeded (`03-plans.sql` line 65, an enterprise `plan_permissions` seed) but no controller/service/`@PreAuthorize` references it (a full grep returns exactly one hit). `AuditController` exposes only two GET search endpoints, both gated on `audit.read` (lines 31 and 47), with no export endpoint — so audit export is effectively unimplemented and there is no governed export path.

**Attack scenario:**
Operationally, `audit_log` grows without bound and the only deletion mechanism is trigger removal; an operator under storage pressure disables the trigger to prune rows, simultaneously creating the tamper window described in the trigger-bypass finding. No record of who exported or pruned audit data exists.

**Recommended fix:**
Define an explicit retention strategy: time-based partitioning of `audit_log` with a privileged, audited archival job (run by a separate role allowed to detach/drop old partitions) that ships data to WORM/cold storage before removal, rather than leaving the only deletion path as "disable the immutability trigger." Either implement and guard an export endpoint with the existing `admin.audit.export` permission or remove the dead permission.

- [ ] Status: open

---

## [SEC-060] Global audit log readable by any holder of audit.read, which is broadly granted (incl. org VIEWER role)

- **Severity:** LOW (audit verdict: confirmed; currently mitigated, not exploitable by a low-privileged user)
- **Dimension:** audit-integrity
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/audit/AuditController.java` — `globalSearch` `@PreAuthorize("hasAuthority('audit.read')")` (lines 30-44, `repo.search` with no org filter); permission grants in `02-rbac.sql` lines 70-95 (SUPER_ADMIN line 72, ORG_OWNER line 77, ORG_ADMIN line 83, VIEWER line 93).

**Description:**
The global, cross-tenant endpoint `GET /api/v1/audit` (`repo.search`, no org filter) is gated only by a flat `hasAuthority('audit.read')` check. In the RBAC seed, `audit.read` is assigned not just to SUPER_ADMIN but also to ORG_OWNER, ORG_ADMIN, and even the read-only VIEWER role. Whether a non-platform user can actually reach the global endpoint depends on whether they hold such a role with a NULL (global) org scope: `AuthController.login` resolves authorities with `orgId=null` (line 105) and `PermissionService.permissionsFor` with `orgId=null` counts only `ur.orgId IS NULL` assignments (lines 34-39), so a purely org-scoped grant does not currently leak into the token-borne authority set. The risk is that the global endpoint authorization is coarse (a single broadly-granted permission, not a platform-admin-only authority) while an org-scoped endpoint already exists for tenant reads, so any future change that surfaces org-scoped authorities into the principal, or a global VIEWER assignment, would expose all tenants' audit data (other orgs' actor emails/IPs/targets) to a low-privileged reader.

Verifier calibration: The current mitigation is confirmed — a normal org-scoped VIEWER/ORG_ADMIN cannot reach the global endpoint today. Reaching the exposed state requires either (a) a future change surfacing org-scoped authorities into the principal, or (b) a global (`org_id NULL`) VIEWER/ORG_ADMIN assignment — but creating such a global assignment via `RbacController.assignRole` (lines 50-70) is itself gated by `hasAuthority('user.write')`, a privileged action, not a low-privilege self-escalation. So it is a real but currently-mitigated hardening gap; low/info is appropriate.

**Attack scenario:**
A user is assigned a global (`org_id NULL`) VIEWER or ORG_ADMIN role (which the seed grants `audit.read`) and calls `GET /api/v1/audit?size=100`, receiving the full cross-tenant audit stream (every org's actor IDs, emails surfaced in the UI, IPs, and action targets), violating tenant isolation for audit confidentiality.

**Recommended fix:**
Gate the cross-tenant `/api/v1/audit` endpoint on a distinct platform-only authority (e.g. `audit.read.global` or SUPER_ADMIN) rather than the same `audit.read` used for org-scoped reads, and stop granting `audit.read` to org VIEWER unless intended. Keep org-scoped reads on `/orgs/{orgId}/audit`, which is already correctly constrained.

- [ ] Status: open

---

## [SEC-061] Session token validation does not verify the issuer claim

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** auth-session
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/SessionTokenService.java` — `parse()` lines 91-106 (issuer set at `issue()` line 61 but never checked); header `typ` set to JWT at line 72 but never asserted.

**Description:**
`issue()` stamps issuer `control-panel` (line 61) but `parse()` reads subject/email/super_admin/authorities/exp and never validates the `iss` claim (or the `typ` header). Validation relies solely on the HMAC signature with the shared secret (line 88) plus an expiry check. A grep confirms there is no `DefaultJWTClaimsVerifier`/`JWTClaimsSetVerifier` or `getIssuer()`/`getType()` check anywhere in the validation path, and `JwtAuthFilter` delegates entirely to `parse()` with no additional checks. This is low impact in isolation because the same secret (`APP_AUTH_SESSION_SECRET`) is consumed by exactly one component (`SessionTokenService` — the only HS256 signer; `LicenseIssuer` uses EdDSA with a separate key and type `license+jwt`), so there is no currently-exploitable cross-token-acceptance path. It is a missing defense-in-depth check.

**Attack scenario:**
If the session secret is shared with or reused by another HS256-signing component (now or in future), tokens minted for that other purpose would be silently accepted as control-panel admin sessions because issuer/audience/type are never asserted.

**Recommended fix:**
Validate the issuer in `parse()` (reject if `iss != ISSUER`) and consider asserting an audience claim and the `typ: JWT` header. Nimbus `DefaultJWTClaimsVerifier` / `JWTClaimsSet` matching makes this straightforward.

- [ ] Status: open

---

## [SEC-062] Tokens with no expiration are accepted as valid indefinitely

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** auth-session
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/SessionTokenService.java` — `parse()` line 93 and line 106.

**Description:**
The expiry check is `if (exp != null && exp.toInstant().isBefore(Instant.now()))` (line 93) and the returned `ParsedToken` carries `exp == null ? null : exp.toInstant()` (line 106). A correctly-signed token that lacks an `exp` claim is therefore treated as never-expiring rather than rejected — a fail-open condition instead of fail-closed. The sole consumer, `JwtAuthFilter.doFilterInternal` (lines 52-87), only calls `tokenService.parse(token)` and trusts the `ParsedToken` with no independent expiry re-check. `issue()` (the only token producer, called from `AuthController` line 106) always sets `expirationTime` (line 67), and `parse()` requires a valid HS256 signature (line 88) using the symmetric secret (enforced `>= 32` bytes at startup, lines 45-52), so this is not directly exploitable without the signing secret. Additional minor gaps: no clock-skew tolerance and no `nbf`/`iat` validation (and no issuer check — see SEC-061).

**Attack scenario:**
Any future code path (or a misconfiguration/library that produces a token without `exp`) yields a permanent, non-expiring session that the verifier accepts forever. Anyone able to sign (holder of the symmetric secret) can mint a never-expiring admin token.

**Recommended fix:**
Treat a missing `exp` as invalid: reject when `exp == null`. Consider using Nimbus's `DefaultJWTClaimsVerifier` with required `exp` (and a small acceptable clock skew) instead of hand-rolled checks.

- [ ] Status: open

---

## [SEC-063] Login lockout is in-memory and per-instance, not shared or persistent

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** auth-session
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/LoginAttempt.java` — `ConcurrentHashMap` state, lines 19-57; consumed in `AuthController.login` lines 85-103.

**Description:**
Brute-force protection is a `ConcurrentHashMap` keyed by lowercased email held in the application process (lines 19-57). It resets on restart/redeploy and is not shared across instances. It is the ONLY brute-force defense on `/login` — `AuthController.login` gates solely on `loginAttempt.isLocked(email)` and updates state via `recordFailure`/`recordSuccess`, with no DB- or Redis-backed counter. The application is intended to run as a Dockerized control panel and already depends on Redis (`application.yml` lines 17-19; `spring-boot-starter-data-redis` declared; `redis:7` service in `docker-compose.yml`), yet `LoginAttempt` does not touch Redis. Although `bucket4j-spring-boot-starter` is a declared dependency, a full grep shows zero bucket4j/RateLimit/RedisTemplate usage in Java code. So a horizontally scaled deployment defeats the lockout: 5-attempt counters are tracked independently per pod, multiplying the effective attempt budget, and a rolling restart clears all lockouts.

Verifier calibration: Low is appropriate — the shipped `docker-compose` runs a single API instance with no replicas, so the `5*N` multiplication only manifests if an operator scales horizontally; passwords are still BCrypt-verified on every attempt (defense-in-depth, not an auth bypass); per-instance lockout still imposes real friction. The restart-clears-lockout weakness applies even single-instance but is minor.

**Attack scenario:**
In a multi-replica deployment behind a load balancer, an attacker spreads password-guessing attempts across N pods, getting roughly `5*N` attempts per window instead of 5, and any deploy/restart wipes accumulated lockouts — substantially weakening the credential-stuffing defense for admin accounts.

**Recommended fix:**
Back the login-attempt counter with the already-available Redis (atomic `INCR` + `EXPIRE`) or the database so limits are enforced cluster-wide and survive restarts. Consider also rate-limiting by source IP, not only by email.

- [ ] Status: open

---

## [SEC-064] Missing function-level access control on RBAC catalog reads (roles and permissions enumerable by any authenticated user)

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** authz-rbac
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/rbac/RbacController.java` — `listRoles()` `GET /api/v1/rbac/roles` (lines 38-42) and `listPermissions()` `GET /api/v1/rbac/permissions` (lines 44-48) — no `@PreAuthorize` present (no class-level `@PreAuthorize` either).

**Description:**
The two GET endpoints in `RbacController` have no method-level authorization. The only gate is `SecurityConfig`'s blanket `.requestMatchers("/api/**").authenticated()` (line 86), so ANY authenticated principal (including a freshly JIT-provisioned SSO user with zero permissions, or a low-privilege VIEWER) can enumerate the full set of system roles and the complete permission catalog, including system roles and every fine-grained permission code. The mutating siblings `assignRole` (line 51) and `removeRole` (line 73) ARE gated with `@PreAuthorize("hasAuthority('user.write')")`, proving the two reads are an intentional-pattern gap (compare `CrlController` line 27 `@PreAuthorize("permitAll()")` which explicitly marks intentionally-public endpoints). `@EnableMethodSecurity(prePostEnabled=true)` is on (line 25), so method security is active for annotated methods — but unannotated methods get no method-level check. This leaks the platform's RBAC structure, which aids privilege-escalation attacks (e.g. learning the exact `roleCode` `SUPER_ADMIN` and permission codes to target). The JIT scenario is substantiated: `SsoSuccessHandler.jitCreateUser` (lines 60-70) creates users with `superAdmin(false)` and at most `OrgMember.Role.MEMBER` (line 77), yielding no fine-grained authorities, yet the principal is authenticated and passes the `/api/**` gate.

**Attack scenario:**
A newly provisioned, unprivileged user (e.g. via SSO JIT with role MEMBER) calls `GET /api/v1/rbac/roles` and `GET /api/v1/rbac/permissions` and receives the full list of roles (including SUPER_ADMIN, with `isSystem`) and all permission codes, mapping out exactly which `roleCode` and permissions to target for the `user.write`-based escalation.

**Recommended fix:**
Add `@PreAuthorize` to both read endpoints requiring an appropriate read permission (e.g. a `role.read` / `rbac.read` authority, or at minimum `user.read`) so the RBAC catalog is not exposed to every authenticated user.

- [ ] Status: open

---

## [SEC-065] Master key accepts arbitrary-length low-entropy secrets via silent SHA-256 fallback

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** crypto-keys
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/keys/KeyEncryptor.java` — `KeyEncryptor.init()` lines 47-63 (length floor at `< 16` throws; direct use at lines 54-55; SHA-256 fallback at lines 56-62, comment lines 51-52). Deployment context: `README.md` line 15, `docker-compose.yml` line 42, `application.yml` line 40 (`APP_KEY_ENC_MASTER`).

**Description:**
`init()` requires only that the base64-decoded master key be `>= 16` bytes. If the decoded length is exactly 16/24/32 it is used directly as the AES key; otherwise the code silently runs SHA-256 over the raw bytes to produce a 32-byte AES key. The inline comment states this is so "test/staging fixtures with arbitrary entropy still work without redeploying." This means a deployer can supply a short, guessable, or non-base64-random passphrase, and the system will accept it and stretch it with a single un-salted, un-iterated SHA-256 — no PBKDF2/scrypt/Argon2, no salt, no iteration count. The effective security collapses to the entropy of whatever string was passed, with no minimum-entropy enforcement beyond a 16-byte length floor. The resulting AES key directly protects all Ed25519 signing private keys (`encrypt()`/`decrypt()` use `keySpec`), so a recovered master secret yields license-forgery capability. The README guidance (`openssl rand -base64 32`) is correct, but nothing in the code enforces a high-entropy key.

Verifier calibration: Real, kept at low. Two caveats: (1) it requires operator misconfiguration — deviating from the documented `openssl rand -base64 32` guidance (which decodes to exactly 32 bytes and never hits the weak path); (2) the originally-cited "17-char ASCII string" example is imprecise — the env value is base64-DECODED first, and a 17-char ASCII string would decode to ~12 bytes (below the 16-byte floor → rejected) or throw if not valid base64. The substance (silent single-SHA-256 stretching of any length-valid low-entropy base64 secret with no KDF/salt/entropy check) is fully confirmed.

**Attack scenario:**
An operator follows a copy-pasted runbook and sets `APP_KEY_ENC_MASTER` to a memorable passphrase (decodes to a non-16/24/32 length, so the SHA-256 branch fires). An attacker who exfiltrates the encrypted `signing_keys` blobs runs an offline dictionary/brute-force attack over candidate passphrases, hashing each with a single SHA-256 (extremely cheap, GPU-friendly), and recovers the AES key, then every Ed25519 signing private key — allowing forging of arbitrary valid licenses.

**Recommended fix:**
Reject non-16/24/32-byte decoded keys outright in production (the direct-key path is the only safe one), or if passphrase input must be supported, derive the AES key with a salted, high-cost KDF (Argon2id/scrypt/PBKDF2 with a stored salt and high iteration count) rather than a bare SHA-256. At minimum, gate the SHA-256 convenience branch behind a non-production profile so it cannot be reached in prod.

- [ ] Status: open

---

## [SEC-066] Incorrect RFC 8032 sign-bit handling in Ed25519 public-key raw-byte fallback can publish a wrong public key in JWKS

- **Severity:** LOW (audit verdict: confirmed; latent — fails closed, unreachable in shipped config)
- **Dimension:** crypto-keys
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/keys/KeyService.java` — `extractRawEd25519PublicBytes()` lines 204-227 (fallback branch 213-224); consumed by `toJwk()` (JWKS publication, line 166) and `LicenseIssuer.signJwt()` (line 99); `JwksController.jwks()`; verifier `resolveVerifier()`/JWKS trust.

**Description:**
The primary path (`spki.length == 44`) correctly slices the trailing 32 raw bytes of the X.509 SubjectPublicKeyInfo and is correct for the standard SunEC encoding. The defensive fallback for "oddly-encoded providers" is wrong: it takes `EdECPublicKey.getPoint().getY().toByteArray()` (a big-endian, possibly sign-extended BigInteger), right-aligns it into a 32-byte big-endian buffer, then sets the RFC 8032 x-coordinate sign bit via `raw[31] |= 0x80`. RFC 8032 §5.1.2 encodes an Ed25519 public point as 32 little-endian octets of the y-coordinate, with the x-sign bit as the MSB (bit 7) of the LAST little-endian octet. Writing the y-coordinate in big-endian byte order and OR-ing `0x80` into `raw[31]` (the least-significant byte in a big-endian layout) is wrong on two counts: the whole 32 bytes are byte-reversed relative to the little-endian encoding Nimbus expects, and the sign bit lands on the wrong octet. Any key generated by a JCE provider that returns a non-44-byte SPKI would be published to `/.well-known/jwks.json` with corrupted bytes (and produce a malformed signing OKP), and verifiers would reject every license signed by that kid (fail-closed, not a forgery risk). With the default SunEC provider the 44-byte path is always taken, so the fallback is currently dead code — no BouncyCastle/FIPS provider is configured in gradle/code.

**Attack scenario:**
Operations swaps in a FIPS or BouncyCastle JCE provider for compliance. The next key rotation produces an `EdECPublicKey` whose `getEncoded()` is not exactly 44 bytes, so the fallback fires and JWKS publishes a corrupted public key for the new ACTIVE kid. All customer Docker apps refresh JWKS, then reject every newly issued license (signature-invalid / kid maps to wrong key), causing a fleet-wide licensing outage that is hard to diagnose because the panel itself signs and stores without error.

**Recommended fix:**
Drop the BigInteger/sign-bit reconstruction. Derive raw Ed25519 public bytes provider-independently from the SPKI by parsing the BIT STRING of the SubjectPublicKeyInfo (the raw key is always the final 32 bytes of the DER for Ed25519, regardless of total length), or use a well-tested library encoder. Do not hand-roll the little-endian/sign-bit logic; if a fallback is kept, encode little-endian and set the sign bit on the final little-endian byte, and add a unit test that round-trips JWKS bytes through the verifier.

- [ ] Status: open

---

## [SEC-067] Raw SQL string concatenation in LISTEN/NOTIFY outbox dispatcher (injection-class defect)

- **Severity:** LOW (audit verdict: confirmed; latent — no current attacker-controlled path)
- **Dimension:** injection-validation
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/events/OutboxPublisher.java` — `private void notify(UnpublishedRow row)`, line 69. Payload assembled in `publishBatch()` (lines 35-43). Schema: `11-outbox.sql` lines 6-8 (`aggregate_id VARCHAR(128)`, `aggregate_type VARCHAR(64)`, `event_type VARCHAR(128)` — no UUID/CHECK constraint).

**Description:**
`OutboxPublisher.notify()` builds a raw SQL statement via string concatenation and executes it on a `java.sql.Statement` instead of a parameterized PreparedStatement:

```java
st.execute("NOTIFY " + CHANNEL + ", '" + json.replace("'", "''") + "'");
```

The payload `json` is re-serialized from `outbox_events` columns (`eventId`, `eventType`, `aggregateType`, `aggregateId`) read back from the database. The only defense is hand-rolled single-quote doubling (`'` → `''`). This is the classic SQL-injection anti-pattern: a value interpolated into SQL command text and protected only by manual escaping. PostgreSQL `NOTIFY` does not accept bind parameters via the simple syntax, but the safe form `SELECT pg_notify(?, ?)` does — which this code does not use. Today the interpolated fields are all server-generated (e.g. `jti = "lic_"+UUIDv7 hex`; `aggregateId` values are `UUID.toString()`/jti/kid), so there is no currently-reachable attacker-controlled single quote — hence low rather than high. However, `aggregate_id`/`aggregate_type`/`event_type` are free-text VARCHAR columns with no DB-level constraint forcing UUIDs, so any future caller of `OutboxPublisher.publish()` passing user-derived text reintroduces a second-order SQL injection here. Manual `''`-escaping is also brittle against backslash/encoding edge cases depending on `standard_conforming_strings`.

**Attack scenario:**
A developer later adds an outbox event whose `aggregateId` or `eventType` carries operator-supplied text (e.g. an org slug, an API key name, or a license `notes`/reason field). That text is persisted to `outbox_events`, then read back by `publishBatch()` and concatenated into the NOTIFY command. A value such as `x'); DROP TABLE outbox_events; --` (or a payload abusing dollar-quoting/backslash handling) injected into `aggregateId` is executed verbatim by the scheduled publisher under the application's DB credentials, with no authenticated request in scope at execution time. This is a stored/second-order SQLi waiting for an upstream change.

**Recommended fix:**
Replace the concatenated statement with a parameterized call: `jdbc.update("SELECT pg_notify(?, ?)", CHANNEL, json);`. This passes both the channel and the payload as bind parameters and eliminates the manual escaping entirely. Do not rely on `value.replace("'", "''")` for SQL safety anywhere.

- [ ] Status: open

---

## [SEC-068] Retired signing keys remain trusted for 18 months with no per-key kill switch

- **Severity:** LOW (audit verdict: confirmed; upper edge of low / borderline medium)
- **Dimension:** license-integrity
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/keys/KeyService.java` — `listPublishedKeys()` lines 154-158 (`RETENTION_MONTHS = 18`, line 42; status transition ACTIVE→RETIRED in `generateNewActiveKey()` lines 80-84); `JwksController.jwks()` (line 25); verifier `LicenseVerifier.resolveVerifier()` (lines 151-186). Enum `SigningKey.Status` `{ACTIVE, RETIRED}` (`SigningKey.java` line 27); DB CHECK `status IN ('ACTIVE','RETIRED')` (`05-signing-keys.sql` line 10).

**Description:**
The JWKS endpoint publishes ACTIVE plus all RETIRED keys retired within an 18-month window (`RETENTION_MONTHS`). The verifier (`resolveVerifier`) builds a working `Ed25519Verifier` for ANY kid present in the fetched JWKS without checking the `SigningKey` status — a RETIRED key verifies exactly like an ACTIVE one (the verifier only ever sees public keys via `PublicKeyProvider`/`JwksParser`, so status never reaches the client; the JWK built in `toJwk()` carries no status field). There is no COMPROMISED/REVOKED state and no per-key revocation: `KeyService` only ever transitions ACTIVE→RETIRED, and `KeyController` exposes only `rotate` and `list`. Consequently a retired/leaked private key remains a fully valid signing key for up to 18 months — capable of forging arbitrary licenses (any plan, permissions, seats, exp) that pass full verification — with no operator remedy short of editing the DB or the retention constant. This contrasts sharply with the rest of the system, which has full revocation paths for license tokens (`LicenseRevocationService` + CRL endpoint) and API keys (`ApiKeyService.revoke`).

Verifier calibration: Low retained — exploitation requires the strong precondition of an already-leaked private key, and the offline JWKS-caching trust model means any revocation mechanism would still depend on client refresh. It sits at the upper edge of low (borderline medium) because the root signing key is the system's trust anchor.

**Attack scenario:**
A signing private key is leaked (or an old key's storage/KMS is compromised). For up to 18 months an attacker who holds that key can forge arbitrary licenses (any plan, permissions, seats, exp) that pass full verification, because the kid is still in the published JWKS and the verifier does not distinguish retired/compromised keys. Operators have no mechanism to revoke just that key short of editing retention/DB by hand.

**Recommended fix:**
Add an explicit COMPROMISED/REVOKED key status that is immediately excluded from `listPublishedKeys()` (independent of the retention window), and provide an admin action to set it. Keep the 18-month window only for normal rotation. Consider tightening the retention window to the maximum license TTL plus margin rather than 18 months.

- [ ] Status: open

---

## [SEC-069] Revocation reason is optional and unvalidated; no machine-readable reason code or revocation-effective semantics captured

- **Severity:** LOW (audit verdict: confirmed; data-modeling/operability gap, fails closed)
- **Dimension:** license-revocation
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/licenses/LicenseController.java` (`RevokeRequest` record line 140 — `reason` has no `@NotBlank`; `revoke()` passes null when body absent, line 102; `@RequestBody(required=false)` line 100); `control-panel-api/src/main/java/com/example/cp/licenses/LicenseRevocationService.java` (`markRevoked()` lines 26-49 — accepts null reason, emits empty string at line 46, conditional audit at line 39). Schema: `06-licenses.sql` line 11 (`revoked_at`), line 12 (`revoke_reason TEXT`). CRL: `CrlController.java` lines 37-38.

**Description:**
`RevokeRequest.reason` carries no validation annotation and the body itself is `required=false`, so a revocation can be recorded with a null/blank reason; the outbox event then publishes reason as an empty string (`LicenseRevocationService` line 46). A null reason also yields no audit reason field at all (line 39 conditional). There is no enumerated reason code (keyCompromise, cessationOfOperation, supersession, etc.) — `LicenseToken` stores only a free-text `revokeReason` string and the DB schema stores `revoke_reason` as free-text TEXT with no code/category column. There is no separately recorded "revocation effective" time distinct from `revokedAt` — `revoked_at` doubles as both the audit timestamp and the only effectivity marker, and the public (`permitAll`) CRL endpoint `/api/v1/licenses/revoked` exposes only `revokedAt` as effectivity and surfaces null/empty reasons (`CrlController.java:38`). For an enterprise license system this means revocations cannot be reliably categorized (e.g. distinguishing a security revocation that must fail closed immediately from a billing revocation), and audits/CRL consumers receive empty reasons.

Verifier calibration: Real data-modeling/operability gap, not an exploitable vulnerability — revocation itself still functions and fails closed (download blocks REVOKED tokens at `LicenseController.java:73-75`). Impact is limited to forensic categorization, audit completeness, and CRL consumer differentiation. Low is appropriate (arguably info, but low defensible given the audit/forensic and fail-closed-categorization concerns).

**Attack scenario:**
Not directly exploitable, but operationally significant: a key-compromise revocation and a routine billing revocation are indistinguishable to downstream consumers and audit. An incident responder cannot programmatically identify which revocations were security-critical, and an empty reason in the audit/outbox stream hampers forensic review of who revoked what and why.

**Recommended fix:**
Make `reason` mandatory (e.g. `@NotBlank` or require the body) and add a constrained `reasonCode` enum (mirroring CRL reason codes) persisted in its own column and included in the signed CRL/outbox payload. Optionally record a distinct `effectiveAt` timestamp so revocation effectivity can differ from the audit write time, and surface `reasonCode` to consumers so security revocations can be enforced with stricter (fail-closed, immediate) semantics.

- [ ] Status: open

---

## [SEC-070] Global audit log search exposes all tenants' audit entries to any holder of global 'audit.read'

- **Severity:** LOW (audit verdict: confirmed; depends on a broad global role assignment)
- **Dimension:** multitenancy-idor
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/audit/AuditController.java` — `globalSearch()` lines 30-44 (`@PreAuthorize("hasAuthority('audit.read')")`, `repo.search` line 42 — no org predicate, vs `searchForOrg` lines 33-50 which filters `WHERE a.actorOrgId = :orgId`). Seed grants `02-rbac.sql` L78/L84/L94 (ORG_OWNER/ORG_ADMIN/VIEWER).

**Description:**
`GET /api/v1/audit` performs an unrestricted, cross-tenant audit search (`repo.search`, L42) gated only by the global `audit.read` authority. The returned `AuditLogDto` (L71-87) exposes `actorUserId`, `actorOrgId`, `targetType`/`targetId`, `payloadJson` and `ipAddress` across all tenants. `audit.read` is granted to the ORG_OWNER/ORG_ADMIN/VIEWER seed roles; if any such role is assigned globally (which, per the authorities-resolution flaw, is how these roles are made functional) or carried as an API-key scope, the holder reads every tenant's audit trail. The authorities model is flat and non-scoped: `JwtAuthFilter` installs `SimpleGrantedAuthority` codes (L70-77) and `AuthenticatedUser.hasAuthority` just checks set membership, so `@PreAuthorize` cannot distinguish a globally-held `audit.read` from an org-scoped one. The org-scoped variant `GET /api/v1/orgs/{orgId}/audit` correctly scopes via `searchForOrg`, but its `hasAuthority('audit.read') or ...` clause (L47) likewise lets a global `audit.read` holder read any specified org's audit log. The exposure is realized when an org-level role (VIEWER/ORG_ADMIN/ORG_OWNER) is assigned GLOBALLY: `RbacController.assignRole` (L50-70) accepts a nullable `orgId` with no guard, and `PermissionService.permissionsFor`'s `orgId==null` branch (L34-39) surfaces `audit.read` into the global authority set baked into the session JWT at login.

Verifier calibration: A purely org-scoped VIEWER (orgId set) does NOT get `audit.read` globally, so exploitation depends on a permitted-but-broad global role assignment — which is why low severity is appropriate. (Closely related to SEC-060.)

**Attack scenario:**
A VIEWER-equivalent account or API key with global `audit.read` enumerates the global audit feed and harvests cross-tenant administrative activity, target ids (subscription/license ids), and source IPs to plan further IDOR attacks.

**Recommended fix:**
Restrict `GET /api/v1/audit` (global feed) to super_admin / dedicated platform-staff authority; for tenant users require an explicit org and route through the org-scoped, org-filtered query. Do not let a generic tenant-level `audit.read` read the platform-wide log.

- [ ] Status: open

---

## [SEC-071] In-memory login-attempt map has no eviction, allowing unbounded memory growth (DoS)

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** password-bruteforce
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/LoginAttempt.java` — `counters` ConcurrentHashMap, line 19; `recordFailure()` lines 35-52; `recordSuccess()` lines 54-57 (line 56 removes only exact email key); `isLocked()` lines 21-33.

**Description:**
Entries are only removed on a successful login for that exact email (`recordSuccess()`, line 56), invoked solely after a fully successful login (`AuthController.java:103`). For any email that fails and never subsequently logs in successfully (every probed non-existent or attacker-chosen address), the `Counter` entry lives forever — the window/lockout logic is purely logical: when the window expires, `recordFailure()` (lines 40-44) overwrites the value with a fresh `Counter` inside `compute()` but never evicts the key. `isLocked()` also never removes anything. There is no size cap, no `removeIf`, and no `@Scheduled` cleanup on this class. Notably the codebase already knows this eviction pattern (`ControlPanelApplication` has `@EnableScheduling`, `OutboxPublisher` uses `@Scheduled`, `LicenseEnvelopeCache.evictStale()` lines 40-42 purges stale entries via `removeIf`) but `LoginAttempt` applies none. An attacker submitting logins for millions of distinct valid-format emails (the `@Email` constraint does not meaningfully cap cardinality, e.g. `a1@b.com`, `a2@b.com`, ...) grows the map without bound — a genuine unbounded-memory / heap-exhaustion DoS.

Verifier calibration: Low appropriate — each entry is small (key string + tiny `Counter`), `@Email` + `@NotBlank` validation forces well-formed inputs, and reaching OOM requires sustained high-volume unauthenticated traffic that network/edge rate limiting would normally blunt; a slow resource-exhaustion vector, not an immediate compromise.

**Attack scenario:**
An attacker scripts logins with millions of unique random email values. Each creates a permanent `ConcurrentHashMap` entry (key string + `Counter` object). Over time this exhausts heap and OOM-kills the API node, taking down the license control panel.

**Recommended fix:**
Use a bounded, time-evicting structure (e.g. Caffeine cache with `expireAfterWrite`/`maximumSize`) or the shared Redis with TTLs, so stale counters are reclaimed. Reject/normalize obviously invalid emails before recording.

- [ ] Status: open

---

## [SEC-072] Password reset token raw value can be returned in the HTTP response when a system property is set

- **Severity:** LOW (audit verdict: confirmed; secure default, latent on misconfiguration)
- **Dimension:** password-bruteforce
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/AuthController.java` — `requestReset()` lines 157-162 (`System.getProperty("app.auth.expose-reset-token", "false")` at line 159, `response.put("reset_token", rawToken)`); takeover chain via `confirmReset()` lines 165-186. Doc: `docs/api/openapi.yaml:127`.

**Description:**
When the JVM system property `app.auth.expose-reset-token` parses to `true`, the raw reset token is placed directly in the JSON response body. This is gated by `System.getProperty` with a safe default of `false` and is documented as dev/test-only (`docs/api/openapi.yaml:127` states `reset_token` is "Only present when app.auth.expose-reset-token=true (dev/test)"), so it is not enabled by the shipped config. The risk is that this is a `-D` JVM flag rather than a Spring profile/`@Value` binding, so it can be enabled in production without showing up in `application.yml` review (the only other `app.auth.*` uses are `@Value`-bound in `SessionTokenService.java`; this one is not). If enabled, it turns the otherwise non-enumerating reset endpoint into a full account-takeover primitive (the requester learns the reset token for any email they submit; `confirmReset()` accepts that raw token, hashes it, and calls `userService.setPassword` for the target user).

Verifier calibration: Genuine defense-in-depth/hardening gap with a secure default. The originally-cited "operator copies a dev run command" scenario is NOT substantiated — there is no dev/run/start script in the repo, the Dockerfile uses a plain `ENTRYPOINT ["java","-jar","/app/app.jar"]` with no `JAVA_OPTS`, and `docker-compose.yml`/`application.yml`/`application-test.yml` never set the property. It is a latent risk dependent on a hypothetical future misconfiguration. Low appropriate (arguably info given the secure default and absence of any in-repo trigger).

**Attack scenario:**
An operator copies a dev run command (which sets `-Dapp.auth.expose-reset-token=true`) into a production launch script. Thereafter anyone can `POST /api/v1/auth/password-reset/request` for a victim email, read `reset_token` from the response, and immediately `POST /password-reset/confirm` to set a new password and take over the account. (Note: no such dev run command exists in the repo today.)

**Recommended fix:**
Bind this behavior to a Spring profile (e.g. `@Profile("!prod")`) or a properties-backed flag that is part of reviewed config, fail fast / log loudly at startup if it is enabled, and ensure it can never be true in the production profile.

- [ ] Status: open

---

## [SEC-073] Password reset flow does not invalidate prior tokens and has no request rate limiting

- **Severity:** LOW (audit verdict: confirmed)
- **Dimension:** password-bruteforce
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/auth/AuthController.java` — `requestReset()` lines 128-163 (token creation lines 142-152); `confirmReset()` lines 165-186. `PasswordResetTokenRepository` (lines 10-13, only `findByTokenHash`). `generateRawToken()` lines 212-213; hashing lines 217-225.

**Description:**
Each call to `/password-reset/request` creates a brand-new token (lines 142-152) via `resetTokenRepository.save(token)` with no preceding delete/invalidate of the user's existing unused tokens; all remain valid until their independent 60-minute TTL (each carries its own `expiresAt = now + 60 min`, line 149; `usedAt` is set only on the specific token used in `confirmReset`). `PasswordResetTokenRepository` exposes only `findByTokenHash` — no `deleteByUserId`/`findByUserId`/bulk-invalidate method. There is no per-email/per-IP rate limit on `requestReset`, and `confirmReset` is also unthrottled: the only limiter in the codebase, `LoginAttempt`, is wired exclusively into `/login`, and a grep for `rate.?limit|bucket4j|throttle|Resilience4j|RateLimiter` found no other throttling. `SecurityConfig` permits `/api/v1/auth/**` with no throttling filter. The token itself is 32 bytes of `SecureRandom` (256-bit) stored only as SHA-256 (lines 143, 217-225), so direct brute force of the token is infeasible and `confirmReset` is not a practical guessing target — hence low. The concrete weaknesses are (a) multiple concurrently-valid reset tokens widen the window for a leaked/intercepted token, and (b) an attacker can spam reset emails to a victim (mail-bomb) and flood the `password_reset_tokens` table with no throttle.

**Attack scenario:**
An attacker repeatedly calls `/password-reset/request` for a victim, generating an unbounded stream of reset emails (harassment / inbox flooding) and many simultaneously-valid tokens; if any single email is intercepted (e.g. via a forwarded or logged URL) it remains usable even after the user requested a newer one.

**Recommended fix:**
On a new reset request, invalidate (mark used or delete) any outstanding unused tokens for that user so only the latest is valid. Add per-email and per-IP rate limiting to `requestReset` (and to `confirmReset` as defense in depth), and schedule cleanup of expired/used tokens.

- [ ] Status: open

---

## [SEC-074] OIDC registration uses CLIENT_SECRET_BASIC with a defaulted empty secret and no PKCE enforcement; issuer-derived endpoints not validated against discovery

- **Severity:** LOW (audit verdict: confirmed; impact limited — token integrity controls remain intact)
- **Dimension:** sso-oidc
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSecurityConfig.java` — `clientRegistrationRepository` lines 50-71 (`clientSecret = cfg.getOrDefault("clientSecret", "")` line 50; `.clientAuthenticationMethod(CLIENT_SECRET_BASIC)` line 61; endpoints concatenated `iss + "/auth"`, `/token`, `/userinfo`, `/jwks` lines 53-57). The only discovery fetch is in `SsoService.test()` (`SsoService.java` lines 79-80), where the response is fetched and discarded.

**Description:**
The registration is built with `clientSecret = cfg.getOrDefault("clientSecret", "")` and `clientAuthenticationMethod(CLIENT_SECRET_BASIC)` unconditionally for every OIDC provider. A repo-wide grep for `pkce|code_challenge|withPkce|ClientRegistrations|fromIssuerLocation` returned ZERO matches, confirming no PKCE support and no branch that switches auth method for a public/secret-less client. When an admin configures a public/PKCE client (no secret), the panel will still send an empty Basic credential and will not switch to PKCE, which can either break the flow or weaken it depending on the OP. Endpoints are derived by string-concatenating the issuer (`iss + "/auth"`, `/token`, `/userinfo`, `/jwks`) when not explicitly provided, rather than using OIDC discovery (`ClientRegistrations.fromIssuerLocation`), so the issuer value in tokens is not authoritatively cross-checked against a discovery document and the defaulted (notably non-standard `/auth`, `/jwks`) endpoints may not match the real OP.

Verifier calibration: Bounding caveat confirmed correct — this is Spring Boot 3.3.4 / Spring Security 6.3 with the AUTHORIZATION_CODE grant and `oauth2Login`; Spring's default `OidcAuthorizationCodeAuthenticationProvider` still validates the `state` parameter and the ID-token signature/nonce against the configured `jwkSetUri`, so token integrity/confidentiality controls are not separately broken. Exploitable impact is genuinely limited (signature/state/nonce validation intact; admins fully control the config; the most likely failure mode of the empty-secret/no-PKCE path against a public client is a broken flow, not a silent security bypass). Low correct.

**Attack scenario:**
(Limited, per the verifier.) A public/PKCE-only OP would receive an empty Basic credential and no `code_challenge`; the most likely effect is a broken flow rather than a silent bypass. The defaulted, unvalidated endpoints may not match the real OP.

**Recommended fix:**
Prefer `ClientRegistrations.fromIssuerLocation(issuer)` so endpoints, issuer, and `jwkSetUri` all come from validated discovery. Select `clientAuthenticationMethod` based on whether a secret is present (`NONE` + PKCE for public clients, `CLIENT_SECRET_BASIC` only when a non-empty secret is configured), and enable PKCE for the authorization-code flow.

- [ ] Status: open

---

## [SEC-075] Default RestTemplate has no timeouts and follows redirects, amplifying SSRF / enabling outbound hangs

- **Severity:** LOW (audit verdict: confirmed; supporting/amplifying weakness, requires authenticated privileged user)
- **Dimension:** ssrf-outbound
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/sso/SsoService.java` — field initializer `private final RestTemplate http = new RestTemplate();` line 26. Reachable via `SsoController.test()` lines 50-54 (`POST /api/v1/orgs/{orgId}/sso/{id}/test`) → `SsoService.test()` lines 72-92 (`http.getForObject()` on concatenated `issuer`/`metadataUrl`, no SSRF guard).

**Description:**
The outbound client used for SSO discovery/metadata fetches is `new RestTemplate()` with no `ClientHttpRequestFactory` configuration (a repo-wide grep for `RestTemplateBuilder`/`ClientHttpRequestFactory`/`setConnectTimeout`/`setReadTimeout`/`setRequestFactory` found zero matches in the backend, and no `@Bean RestTemplate` exists). The default `SimpleClientHttpRequestFactory` has no connect timeout and no read timeout (both default to `-1`, effectively infinite) and sets `HttpURLConnection.setInstanceFollowRedirects(true)`, so HTTP 3xx redirects are followed automatically. This is a supporting weakness for the SSRF findings: an internal host that accepts TCP but never responds will hang the request thread indefinitely (resource exhaustion against the request worker threads), and automatic redirect following lets an attacker-controlled public host 302-redirect the server into an internal target, bypassing any host check applied only to the originally-submitted URL. There is no host allowlist, private-IP block, or SSRF guard anywhere in the codebase.

Verifier calibration: The endpoint is gated by `@PreAuthorize` requiring `sso.write` or org owner/admin, so the attacker must be an authenticated privileged user. This is a supporting/amplifying weakness on top of the underlying SSRF (arbitrary outbound fetch), not a standalone unauthenticated vulnerability. Low retained.

**Attack scenario:**
An attacker submits an SSO test URL pointing at an internal host:port that accepts TCP but never sends an HTTP response; the control-plane request thread blocks forever, and repeated calls exhaust the worker pool (DoS). Alternatively the attacker submits a public URL they control that returns `302 Location: http://169.254.169.254/...`, and the default RestTemplate transparently follows it to the metadata service.

**Recommended fix:**
Construct the RestTemplate with explicit, short connect and read timeouts (a `ClientHttpRequestFactory` with `connectTimeout`/`readTimeout` of a few seconds) and disable automatic redirect following (or re-run host/IP validation on every redirect hop). Cap response size for fetched metadata. Combine with allowlist/IP-pinning validation so the timeouts and redirect handling reinforce, rather than substitute for, proper SSRF defenses.

- [ ] Status: open

---

## [SEC-076] Client-controlled occurredAt buckets quota into arbitrary periods (backdating / future-dating)

- **Severity:** LOW (audit verdict: confirmed; reviewer downgraded from medium to low — latent, pre-enforcement)
- **Dimension:** usage-quota
- **File(s)/location:** `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestService.java` — `ingest()` line 64; `upsertQuota`/`monthStartUtc` lines 85-100 (line 86). `UsageIngestController.EventDto.occurredAt` line 75 (no validation). `UsageQuota.PK` lines 49-75; schema PK at `07-usage.sql:25`. Spec mismatch: `openapi.yaml:1615`.

**Description:**
`occurredAt` is fully client-controlled and trusted: `UsageIngestController.EventDto.occurredAt` (line 75) carries no validation annotation, and `UsageIngestService.ingest()` line 64 only defaults a null value to `now()` — any supplied timestamp (far past or far future) is accepted verbatim. That timestamp is the sole input to `monthStartUtc(occurredAt)` (line 86), which produces `period_start`, and `period_start` is part of the `usage_quotas` primary key (`UsageQuota.PK`; SQL PRIMARY KEY at `07-usage.sql:25`). So a client directly chooses which monthly quota bucket its consumption lands in, and there is no clamp to the current period anywhere in the ingest path. The OpenAPI spec/impl mismatch is also real: `openapi.yaml:1615` marks `occurredAt` required, but the implementation tolerates null.

Verifier calibration: Downgraded from medium to low. The exploit's premise — bypassing a monthly cap — depends on limit enforcement that does NOT exist in this codebase. A grep for `limit_value`/`consumedValue`/`enforce`/`exceed` shows `limit_value` is only ever inserted as NULL (`UsageIngestService.java:90`) and is never read or compared against `consumed_value` to block ingestion; it appears only in the schema, entity fields, and a read-only `QuotaDto` projection. The attack scenario itself concedes this ("once limit enforcement exists"). Therefore there is no live quota cap to bypass today — this is a latent data-integrity / pre-enforcement-bypass flaw: events can populate quota rows for arbitrary/future periods, and any future quota enforcement built on these client-bucketed rows would be defeated. Real and worth fixing before enforcement ships, but not a current medium-severity bypass.

**Attack scenario:**
A tenant nearing its current-month limit submits events with `occurredAt` set to a future month (or a long-past month). The consumption is recorded against a different period bucket than the one being enforced, so current-period quota checks (once limit enforcement exists) see headroom that should not exist, effectively bypassing the monthly cap. Future-dating also creates quota rows for periods that should not yet exist.

**Recommended fix:**
Reject `occurredAt` outside an acceptable window (e.g. not in the future beyond small clock skew, not older than the current/previous open billing period). Bucket quota by a server-side period derived from a validated timestamp, and consider recording server receipt time separately from the client-claimed `occurredAt`.

- [ ] Status: open

---

## [SEC-077] Envelope metadata (expires_at, customer, plan) is fully attacker-controllable and never reconciled against signed JWT claims

- **Severity:** INFO (audit verdict: confirmed; reviewer reduced severity to info — defense-in-depth/documentation matter)
- **Dimension:** license-integrity
- **File(s)/location:** `license-verifier/src/main/java/com/example/licenseverifier/LicenseVerifier.java` — `extractJwt()` lines 86-101 (reads only `envelope.license()` at lines 90, 93); `LicenseEnvelope.java` (lines 8-15 define `issued_at`, `customer`, `plan`, `expires_at`, `notes` with public accessors); consumers reading envelope fields. Demonstrated divergence in test `verifies_jwt_wrapped_in_envelope` (`LicenseVerifierTest.java` lines 96-115). Documented contract: `docs/license-format.md:21`.

**Description:**
`extractJwt()` parses the `.lic` JSON envelope and uses ONLY the `license` field (the JWT); the sibling fields `issued_at`, `customer`, `plan`, `expires_at`, `notes` are read into `LicenseEnvelope` but never validated against the signed JWT claims. The signed JWT is the source of truth for the verifier itself (`toLicense()` builds `License` purely from `JWTClaimsSet`), so this is NOT a verifier bypass. The risk is integrity confusion: any tooling, dashboards, support workflows, or human operators that trust the plaintext envelope fields can be lied to (e.g. envelope says `plan=Enterprise`/`expires_at=2099` while the JWT says `plan=free`/`exp=soon`, or vice-versa). The shipped test even uses an envelope whose `plan` ("Pro") disagrees with the JWT claim ("pro"), and asserts `license.getPlan() == "pro"` (the JWT value), demonstrating that divergence is silently accepted.

Verifier calibration: Reduced to info (not low). There is currently NO consumer in this codebase that trusts the untrusted envelope fields — the Spring starter `LicenseService` (lines 137, 145) reads `lic.plan()`/`lic.expiresAt()` from the verified `License` object, not the envelope, and the control-panel references operate on the server's own trusted issue-time data (`IssuedLicense`), never on an attacker-supplied `.lic` file. `docs/license-format.md:21` explicitly documents the intended contract: "The verifier reads license, validates the JWT, and ignores the rest of the envelope." The attack scenario depends entirely on hypothetical tooling/operators that parse the plaintext envelope instead of the JWT — none of which exist as code here. The residual concern (the `LicenseEnvelope` record publicly exposes accessors for untrusted fields a future consumer could mistakenly trust) is a defense-in-depth/documentation matter, hence info severity.

**Attack scenario:**
An attacker edits the unsigned envelope fields of a legitimate `.lic` file (`expires_at` far in the future, `plan` upgraded). Automated inventory/monitoring or operators reading the envelope (rather than parsing the JWT) misreport the license as valid/upgraded, masking an expired or downgraded entitlement and delaying revocation/renewal action.

**Recommended fix:**
Either drop the redundant plaintext metadata from the consumed envelope contract, or after JWT verification reconcile `envelope.expires_at`/`plan`/`customer` against the verified claims and reject (`LicenseFileMalformedException`) on mismatch. Clearly document that only the JWT is authoritative and that any tooling must read claims, not envelope fields.

- [ ] Status: open
