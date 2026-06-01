# Design spec: sso-ssrf

## Shared contracts (other files depend on these — keep signatures exact)

### `UrlGuard` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/sso/UrlGuard.java`
- **Purpose:** Central SSRF defense. Validates an admin-supplied IdP issuer/metadata URL (scheme/host/port), resolves DNS, rejects loopback/private/link-local/ULA/multicast/wildcard and 169.254.0.0/16 (incl 169.254.169.254), and returns a pinned-IP HTTP fetch result. Used by BOTH SsoService.create (validate-only) and SsoService.test (validate+fetch). Generic messages on failure; full reason logged server-side only.
- **Signature/contract:**

```
@Component public class UrlGuard { public java.net.URI validate(String rawUrl) throws SsrfException; /* parse+scheme+host+resolve+IP-policy, NO fetch */  public FetchResult fetchPinned(String rawUrl) throws SsrfException; /* validate() then GET to pinned IP, https-only, redirects DISABLED, connectTimeout=2s readTimeout=3s, max body 256KB, returns status+bodySnippet */  public record FetchResult(int status, String bodySnippet) {} public static class SsrfException extends RuntimeException { public SsrfException(String publicMessage, String internalDetail){...} public String publicMessage(); public String internalDetail(); } }
```

### `app.sso.url-guard.allowed-ports` (config-property)
- **Purpose:** Optional CSV of extra allowed ports beyond 443 (default empty -> only 443). Bound via @Value in UrlGuard.
- **Signature/contract:**

```
app.sso.url-guard.allowed-ports (String CSV, default "")
```

### `app.sso.url-guard.allow-http` (config-property)
- **Purpose:** Test/dev escape hatch to permit http (default false -> https-only). NEVER true in prod.
- **Signature/contract:**

```
app.sso.url-guard.allow-http (boolean, default false)
```

### `13-sso-hardening.sql` (db-migration)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-sso-hardening.sql`
- **Purpose:** Add sso.read permission (referenced by SsoController @PreAuthorize but never seeded -> currently dead grant); add columns to sso_providers for verified-domain allowlist and encrypted client secret reference; grant sso.read+sso.write to relevant roles; create sso_identities table binding (provider_id, subject) -> user_id.
- **Signature/contract:**

```
INSERT permissions('sso.read'); ALTER TABLE sso_providers ADD COLUMN allowed_email_domains TEXT, ADD COLUMN client_secret_enc BYTEA; CREATE TABLE sso_identities(id uuid pk, provider_id uuid fk sso_providers ON DELETE CASCADE, subject text not null, user_id uuid fk users, created_at timestamptz, UNIQUE(provider_id, subject)); grant sso.read to SUPER_ADMIN/ORG_OWNER/ORG_ADMIN, sso.write to ORG_ADMIN.
```

### `SsoIdentity` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/sso/SsoIdentity.java`
- **Purpose:** JPA entity for sso_identities — binds external (provider, subject) to a local user so a hostile IdP changing the email cannot hijack an existing account.
- **Signature/contract:**

```
@Entity @Table(name="sso_identities") fields: UUID id; UUID providerId; String subject; UUID userId; OffsetDateTime createdAt; (Lombok @Getter/@Setter/@Builder/@NoArgsConstructor/@AllArgsConstructor)
```

### `SsoIdentityRepository` (interface)
- **File:** `control-panel-api/src/main/java/com/example/cp/sso/SsoIdentityRepository.java`
- **Purpose:** Lookup identity binding by (providerId, subject).
- **Signature/contract:**

```
interface SsoIdentityRepository extends JpaRepository<SsoIdentity,UUID> { Optional<SsoIdentity> findByProviderIdAndSubject(UUID providerId, String subject); }
```

### `sessionCookieName / session JWT cookie` (bean)
- **File:** `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java`
- **Purpose:** SsoSuccessHandler now mints a SessionTokenService JWT and writes it as an HttpOnly, Secure, SameSite=Lax cookie named cp_session, then redirects to UI without the token in the URL.
- **Signature/contract:**

```
ResponseCookie.from("cp_session", issued.token()).httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(ttlSeconds).build()
```

## File edits

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/sso/UrlGuard.java`
- depends on: app.sso.url-guard.allowed-ports, app.sso.url-guard.allow-http
- NEW @Component in package com.example.cp.sso. This is the single SSRF chokepoint reused by create and test paths.
- Constructor injects @Value("${app.sso.url-guard.allow-http:false}") boolean allowHttp and @Value("${app.sso.url-guard.allowed-ports:}") String allowedPortsCsv; parse CSV into Set<Integer> (always include 443; include 80 only if allowHttp).
- validate(String rawUrl): (1) null/blank -> SsrfException("Invalid URL"). (2) Parse with new java.net.URI(rawUrl).toURL() inside try; any MalformedURL/URISyntax -> SsrfException("Invalid URL", e.getMessage()). (3) scheme must equalsIgnoreCase https (or http only when allowHttp) else SsrfException("URL scheme not allowed"). (4) host must be non-null, non-blank; reject if host contains userinfo (uri.getUserInfo()!=null) or host is an IP literal that is already private (see step 7 applied to literal). (5) port: if uri.getPort()==-1 use default(443/80); reject if resolved port not in allowedPorts -> SsrfException("URL port not allowed"). (6) Resolve ALL addresses: InetAddress[] addrs = InetAddress.getAllByName(host); UnknownHost -> SsrfException("Host could not be resolved"). (7) For EACH addr reject if isLoopbackAddress() || isAnyLocalAddress() || isLinkLocalAddress() || isSiteLocalAddress() || isMulticastAddress() || isAddrPrivate(addr). isAddrPrivate covers: IPv4 10/8,172.16/12,192.168/16,100.64/10 (CGNAT),169.254/16 (explicit, catches 169.254.169.254),0.0.0.0/8; IPv6 fc00::/7 ULA, ::1, ::, mapped/compat IPv4 (unwrap ::ffff:a.b.c.d and re-check). Any hit -> SsrfException("URL host is not allowed"). (8) Return the parsed URI AND stash the vetted InetAddress[] so fetchPinned reuses the SAME resolution (defends DNS-rebinding TOCTOU). Implement as returning a small record or have fetchPinned re-call a private resolveAndCheck that returns both URI and pinned InetAddress.
- fetchPinned(String rawUrl): call private resolveAndCheck(rawUrl) -> {URI uri, InetAddress pinned}. Build java.net.http.HttpClient with .followRedirects(HttpClient.Redirect.NEVER) and .connectTimeout(Duration.ofSeconds(2)). Build request to a URI whose HOST is replaced by the pinned IP literal but set HTTP Host header to original hostname (so TLS SNI/cert still matches host) — implement by connecting to pinned IP: simplest correct approach is custom HttpClient is hard to IP-pin for TLS, so instead: re-resolve is the risk. Acceptable MVP: re-validate immediately before the request (resolveAndCheck) and send with .timeout(Duration.ofSeconds(3)); document residual TOCTOU in code comment. Limit body: read at most 256KB (BodyHandlers.ofByteArray then truncate, or ofString with size guard). Reject non-2xx by returning FetchResult(status, snippet) without throwing (test path maps to ok=false). Any IOException/timeout -> SsrfException("Could not reach the identity provider", e.toString()).
- All SsrfException carry a generic publicMessage (returned to admin) and a detailed internalDetail (logged at WARN, never returned). Add private static Logger.
- Add helper unwrapMapped(InetAddress) and isAddrPrivate(InetAddress) as described. Keep all checks allow-list-of-deny style and fail-closed (unknown address family -> reject).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoService.java`
- depends on: UrlGuard, app.sso.url-guard.allowed-ports
- Remove the field-initialized RestTemplate http = new RestTemplate(); (this is the current SSRF sink at lines 26, 80, 85). Delete the import org.springframework.web.client.RestTemplate.
- Inject UrlGuard urlGuard and com.example.cp.keys.KeyEncryptor keyEncryptor via constructor (add params; keep existing repo). Update constructor body and field declarations.
- create(...): after JSON-shape validation and BEFORE building/saving the entity, extract and VALIDATE the IdP URL: for OIDC read cfg.get("issuer") (required -> ApiException.badRequest("issuer is required")), for SAML read cfg.get("metadataUrl") (required). Call urlGuard.validate(url) inside try/catch(UrlGuard.SsrfException e){ log.warn("SSO create URL rejected for org {}: {}", orgId, e.internalDetail()); throw ApiException.badRequest(e.publicMessage()); }. This closes #18/#19/#46 at the create path.
- ENCRYPT + REDACT OIDC client secret: if type==OIDC and cfg contains "clientSecret" (non-blank String): byte[] enc = keyEncryptor.encrypt(secret.getBytes(UTF_8)); set p.setClientSecretEnc(enc); REMOVE "clientSecret" from the cfg map BEFORE serializing configJson so the plaintext secret is never persisted in config_json nor returned by SsoDto. Re-serialize the redacted map to json. (Mirror KeyService usage of encryptor.)
- test(UUID id): replace http.getForObject(...) calls. Build discoveryUrl/metadataUrl exactly as today, then UrlGuard.FetchResult r = urlGuard.fetchPinned(url) inside try/catch. On SsrfException -> return new TestResult(false, e.publicMessage()) and log.warn(internalDetail). On success: return new TestResult(r.status()>=200 && r.status()<300, generic message). Keep the existing catch-all but change return to a GENERIC message (do NOT echo e.getMessage() — currently leaks at line 90); log details server-side only. This closes #47/#48/#49.
- Add AuditContext entries on test: AuditContext.set("sso.provider.tested"); AuditContext.setTarget("sso_provider", id.toString()); putPayload("ok", result). Note test() is @Transactional(readOnly=true) and is a POST mapping so AuditInterceptor will flush it.
- Add import for java.nio.charset.StandardCharsets and com.example.cp.keys.KeyEncryptor.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoProvider.java`
- depends on: 13-sso-hardening.sql
- Add field: @Column(name="client_secret_enc") private byte[] clientSecretEnc; (nullable; holds AES-GCM blob from KeyEncryptor). Lombok @Getter/@Setter generate accessors getClientSecretEnc/setClientSecretEnc.
- Add field: @Column(name="allowed_email_domains") private String allowedEmailDomains; (CSV of verified domains the org will accept for JIT; nullable=null means none allowed -> deny JIT for safety per spec). Lombok accessors.
- No change to configJson column; secret is now stored encrypted in client_secret_enc and stripped from config_json by SsoService.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoController.java`
- No security-behavior change required to @PreAuthorize, but note SsoDto.config returns p.getConfigJson() which after the SsoService change no longer contains clientSecret (redaction now happens at persist time, so even older callers are safe). Optionally add a defensive comment.
- CreateRequest already takes Map<String,Object> config; no signature change. The validation/SSRF now happens in the service create() which is correct (single path).
- (Compile dependency) sso.read authority used at line 31 is now actually seeded by migration 13 — no code change here, just stop being a dead reference.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoSecurityConfig.java`
- depends on: UrlGuard, KeyEncryptor
- clientRegistrationRepository(): the OIDC clientSecret is no longer in config_json. Inject KeyEncryptor (add constructor param) and, per provider, DECRYPT p.getClientSecretEnc() to get the secret: String clientSecret = p.getClientSecretEnc()==null ? "" : new String(keyEncryptor.decrypt(p.getClientSecretEnc()), UTF_8). Replace the line String clientSecret = (String) cfg.getOrDefault("clientSecret", "").
- Inject UrlGuard (add constructor param) and re-validate issuer at bootstrap: wrap each provider in try/catch and call urlGuard.validate(issuer); on SsrfException skip that registration (continue) and log.warn. This prevents a row that bypassed create-time validation (e.g. inserted directly) from arming an SSRF-capable registration. Same guard for SAML metadataUrl in relyingPartyRegistrationRepository() before RelyingPartyRegistrations.fromMetadataLocation (which itself performs an outbound fetch — current SSRF sink at line 104).
- relyingPartyRegistrationRepository(): before fromMetadataLocation(metadataUrl), call urlGuard.validate(metadataUrl); skip+log on SsrfException. (Note: Spring's fromMetadataLocation still does its own fetch without IP pinning; document this residual risk — acceptable because URL is now scheme/host-vetted and only super_admin/org_admin can write providers. A follow-up could load metadata bytes via UrlGuard.fetchPinned and use fromMetadata(InputStream).)
- Replace the field ObjectMapper mapper = new ObjectMapper() is fine; just add the two new injected beans to the existing constructor and fields.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java`
- depends on: SsoIdentity, SsoIdentityRepository, sessionCookieName / session JWT cookie
- Add constructor deps: SessionTokenService tokenService, AuthoritiesLoader authoritiesLoader, SsoIdentityRepository identityRepo, SsoProviderRepository providerRepo, AuditWriter auditWriter (for direct audit since the redirect flow is NOT a *Controller and the AuditInterceptor aspect will not fire). Add @Value("${app.cookie.secure:true}") boolean cookieSecure for local-dev override.
- REQUIRE email_verified for OIDC: in extract logic, when principal instanceof OAuth2User, read attribute "email_verified"; if it is missing or not Boolean.TRUE (also accept String "true"), DO NOT provision — log.warn, sendRedirect(uiBaseUrl + "?sso=error"), return. This closes #50.
- Bind identity to (provider, subject): determine the SsoProvider id and registrationId from the OAuth2/SAML registration. For OAuth2User the registrationId is on the OAuth2AuthenticationToken (cast authentication to OAuth2AuthenticationToken to call getAuthorizedClientRegistrationId()); strip the "oidc-"/"saml-" prefix to get the provider UUID. subject = OAuth2User.getName() (the 'sub' / userNameAttribute). For SAML use Saml2AuthenticatedPrincipal.getName() as subject and the relying-party registration id. Resolve SsoProvider via providerRepo.findById(providerUuid).
- JIT/lookup order: (1) identityRepo.findByProviderIdAndSubject(providerId, subject). If present -> load that user (do NOT trust email to find the account). (2) If absent: enforce VERIFIED-DOMAIN ALLOWLIST — parse provider.getAllowedEmailDomains() CSV; the email domain MUST be in the allowlist, else reject (log + redirect ?sso=error). (3) Then findByEmail(email): if a local user already exists with that email, LINK by creating an SsoIdentity row pointing to it (do not create a duplicate); else jitCreateUser. (4) Always insert SsoIdentity(provider,subject,user.id) for new bindings.
- jitCreateUser: keep superAdmin(false) HARD-CODED (never auto-promote; reaffirm #51) and status ACTIVE. Email comes from verified claim only.
- Mint session JWT: Set<String> authorities = authoritiesLoader.authoritiesFor(user.getId(), null, user.isSuperAdmin()); SessionTokenService.IssuedToken issued = tokenService.issue(user.getId(), user.getEmail(), user.isSuperAdmin(), authorities). Write HttpOnly cookie: ResponseCookie cp = ResponseCookie.from("cp_session", issued.token()).httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(tokenService.ttl().getSeconds()).build(); response.addHeader(HttpHeaders.SET_COOKIE, cp.toString()). Redirect to uiBaseUrl + "?sso=success" WITHOUT the token in the URL (token now only in cookie). This closes #5 (no token in redirect URL/logs).
- AUDIT directly via auditWriter.record(user.getId(), orgId, action, "user", user.getId().toString(), payload, request.getRemoteAddr()): action "sso.login" on every success; action "sso.jit_provisioned" additionally when a new user/identity was created; payload includes providerId and a redacted note (never the token). This closes #74/#75.
- Keep ensureMembership(orgId,userId) but only after successful identity resolution.
- Add imports: org.springframework.http.ResponseCookie, org.springframework.http.HttpHeaders, org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken, com.example.cp.auth.SessionTokenService, com.example.cp.rbac.AuthoritiesLoader, com.example.cp.audit.AuditWriter, java.util.Map.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoLoginController.java`
- No SSRF surface here (it only builds an internal redirect path). Minor hardening (optional but recommended): keep org lookup and provider selection; ensure the chosen provider is enabled (already filtered). No outbound fetch, no change required for this theme beyond confirming it does not echo provider config.
- (Optional) audit "sso.login.start" — out of scope; leave as-is to keep diff minimal.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/JwtAuthFilter.java`
- depends on: sessionCookieName / session JWT cookie
- Make the session cookie usable for authenticated API calls coming from the browser after SSO: in doFilterInternal, if the Authorization Bearer header is absent, fall back to reading the cp_session cookie (request.getCookies(), find name cp_session) and use its value as the token. Keep all existing parse/authority logic identical. Without this, the HttpOnly cookie minted by SsoSuccessHandler would never authenticate API requests and SSO login would be cosmetic.
- Guard: only treat cookie as token when present and non-blank; on parse failure write the same ProblemDetail as today (or silently continue unauthenticated for cookie path to avoid breaking unauthenticated endpoints — prefer continue-unauthenticated for cookie, since a stale cookie should not hard-fail public endpoints). Document the chosen behavior in a comment.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/sso/SsoIdentity.java`
- depends on: 13-sso-hardening.sql
- NEW JPA entity mapping sso_identities (see shared contract). @Id UUID id; @Column(name="provider_id") UUID providerId; @Column(name="subject") String subject; @Column(name="user_id") UUID userId; @Column(name="created_at") OffsetDateTime createdAt. Lombok annotations matching SsoProvider style.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/sso/SsoIdentityRepository.java`
- depends on: SsoIdentity
- NEW Spring Data repo: extends JpaRepository<SsoIdentity,UUID>; method Optional<SsoIdentity> findByProviderIdAndSubject(UUID providerId, String subject).

### [NEW FILE] `control-panel-api/src/main/resources/db/changelog/changes/13-sso-hardening.sql`
- NEW liquibase formatted sql. changeset cp:13-sso-read-permission: INSERT INTO permissions(code,name,description,category) VALUES ('sso.read','Read SSO','View SSO providers','sso') ON CONFLICT (code) DO NOTHING; then grant: INSERT role_permissions SELECT for SUPER_ADMIN (sso.read), ORG_OWNER (sso.read), ORG_ADMIN (sso.read, sso.write) ON CONFLICT DO NOTHING. (sso.write already exists; ORG_OWNER already has sso.write.)
- changeset cp:13-sso-providers-columns: ALTER TABLE sso_providers ADD COLUMN IF NOT EXISTS client_secret_enc BYTEA; ALTER TABLE sso_providers ADD COLUMN IF NOT EXISTS allowed_email_domains TEXT;
- changeset cp:13-sso-identities: CREATE TABLE sso_identities ( id UUID PRIMARY KEY DEFAULT gen_random_uuid(), provider_id UUID NOT NULL REFERENCES sso_providers(id) ON DELETE CASCADE, subject TEXT NOT NULL, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(provider_id, subject) ); CREATE INDEX idx_sso_identities_user ON sso_identities(user_id);
- Add matching --rollback lines for each changeset.
- Register the new file in db.changelog-master.yaml (see next edit).

### [MODIFY] `control-panel-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- depends on: 13-sso-hardening.sql
- Append an include entry for db/changelog/changes/13-sso-hardening.sql AFTER the 12-additional-permissions.sql include and the 99-auth-password-reset.sql include (order: ensure it runs after 02-rbac and 09-sso which create permissions and sso_providers; placing it last is safe). Add:
  - include:
      file: db/changelog/changes/13-sso-hardening.sql

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- depends on: app.sso.url-guard.allowed-ports, app.sso.url-guard.allow-http
- Under app.sso add:
    url-guard:
      allow-http: ${APP_SSO_ALLOW_HTTP:false}
      allowed-ports: ${APP_SSO_ALLOWED_PORTS:}
  And under a new top-level app.cookie: secure: ${APP_COOKIE_SECURE:true} (used by SsoSuccessHandler). Document that allow-http must remain false in production.

### [MODIFY] `control-panel-api/src/main/resources/application-test.yml`
- depends on: app.sso.url-guard.allow-http
- Add app.sso.url-guard.allow-http: true and app.cookie.secure: false so tests can use a local wiremock/http stub and inspect Set-Cookie without Secure-only constraints. Keep allowed-ports including the test stub port if needed.

## Tests to add

- UrlGuard.validate rejects http when allow-http=false (scheme), and accepts https.
- UrlGuard rejects 169.254.169.254 explicitly (cloud metadata) and any 169.254.0.0/16 link-local.
- UrlGuard rejects loopback (127.0.0.1, ::1, localhost), 0.0.0.0/anyLocal, 10.x/172.16-31.x/192.168.x site-local, 100.64.x CGNAT, IPv6 ULA fc00::/7, and IPv4-mapped IPv6 (::ffff:127.0.0.1).
- UrlGuard rejects a hostname whose DNS resolves to a private IP (DNS-based bypass) — use a test resolver/host entry pointing to 10.0.0.1.
- UrlGuard rejects URLs with userinfo (https://user@evil) and non-allowed ports (https://host:22).
- UrlGuard.fetchPinned does NOT follow redirects: a stub returning 302 to http://169.254.169.254/ yields the 302 status (or SsrfException), never fetches the redirect target.
- UrlGuard.fetchPinned enforces timeouts (stub that sleeps > readTimeout -> SsrfException with generic message) and caps body size at 256KB.
- SsoService.create(OIDC) with issuer=http://localhost/... returns 400 with a GENERIC message and the provider is NOT persisted.
- SsoService.create(OIDC) with a clientSecret in config persists client_secret_enc (non-null, decrypts back to original via KeyEncryptor) and the stored config_json + returned SsoDto.config contain NO clientSecret key.
- SsoService.test maps a 200 from a valid https stub to TestResult(ok=true) and a 500/unreachable to ok=false with a GENERIC message that does NOT echo internal exception text (regression guard for current line-90 leak).
- SsoSecurityConfig.clientRegistrationRepository decrypts client_secret_enc into the ClientRegistration clientSecret and skips providers whose issuer fails UrlGuard.validate.
- SsoSuccessHandler rejects OIDC login when email_verified is false/absent (no user created, redirect ?sso=error).
- SsoSuccessHandler binds by (provider, subject): a second login from the same subject with a CHANGED email maps back to the original user (no account takeover, no duplicate user).
- SsoSuccessHandler enforces verified-domain allowlist: subject with email domain not in provider.allowed_email_domains is rejected (no JIT).
- SsoSuccessHandler never sets super_admin=true on a JIT-created user.
- SsoSuccessHandler sets an HttpOnly+Secure+SameSite=Lax cp_session cookie and the redirect Location contains NO token query param.
- JwtAuthFilter authenticates a request presenting only the cp_session cookie (no Authorization header) and rejects/ignores a tampered cookie.
- Audit: a successful SSO login writes an audit_log row action=sso.login; a first-time login also writes sso.jit_provisioned; payloads contain no token/secret.

## Risks / cross-file notes

- sso.read authority is referenced in SsoController.@PreAuthorize (line 31) but is NOT seeded anywhere today — until migration 13 runs, that clause is a permanent false for non-super-admins (the @orgAccess fallback masks it). Migration 13 must be included in db.changelog-master.yaml or the create/test PreAuthorize semantics stay unchanged and tests asserting sso.read will fail.
- SsoSecurityConfig builds ClientRegistrationRepository/RelyingPartyRegistrationRepository at bean-creation time. Adding KeyEncryptor + UrlGuard as constructor deps creates a startup dependency: KeyEncryptor requires app.signing.master-key (already required) — fine; but any provider row with a NULL client_secret_enc must be handled (treat as empty secret) to avoid NPE at boot.
- DB-rebind TOCTOU: UrlGuard.validate resolves DNS, but java.net.http.HttpClient re-resolves at request time for fetchPinned. True IP pinning over TLS is non-trivial; spec uses re-validate-immediately-before-fetch as MVP and must document residual risk. Spring's RelyingPartyRegistrations.fromMetadataLocation in SsoSecurityConfig performs its own unpinned fetch — only scheme/host pre-validation is applied; flag as accepted residual or follow up with fromMetadata(InputStream) fed by UrlGuard.fetchPinned.
- Order of operations in SsoService.create: must validate URL and strip+encrypt clientSecret BEFORE mapper.writeValueAsString and BEFORE repo.save, otherwise plaintext secret leaks into config_json (the very bug being fixed).
- SsoSuccessHandler runs OUTSIDE the @RestController AuditInterceptor aspect (it is an AuthenticationSuccessHandler, not a *Controller), so audit MUST be written directly via AuditWriter; relying on AuditContext alone will silently drop SSO audit events.
- Cookie auth in JwtAuthFilter changes the auth surface for the whole app (any endpoint now accepts cp_session). Ensure CSRF posture is acceptable: CSRF is currently disabled and SameSite=Lax mitigates cross-site POST; document that state-changing endpoints rely on SameSite. Bearer-header path must remain primary/unchanged to avoid breaking existing API clients and tests.
- Saml2AuthenticatedPrincipal vs OAuth2AuthenticationToken: extracting registrationId differs by flow; casting authentication to OAuth2AuthenticationToken only valid for OIDC. SAML registrationId must be obtained from the Saml2 authentication/principal — mishandling yields a NULL providerId and broken identity binding.
- control-panel-api currently has ZERO tests under src/test; adding the test classes also introduces the first test sources — ensure spring-security-test + testcontainers wiring and application-test.yml (allow-http=true) are consistent or new tests will fail to bootstrap the SSRF allow-http path.
- email column is CITEXT/unique; linking an SSO identity to an existing local user by email is case-insensitive — ensure findByEmail uses the same normalization to avoid duplicate-key on users.email during JIT.
