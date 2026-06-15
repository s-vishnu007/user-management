# `com.example.cp.sso` — Single Sign-On (OIDC + SAML)

> Module overview. This package is the control panel's **enterprise SSO** subsystem. It lets an org admin register one or more identity providers (IdPs) — either **OIDC** (OpenID Connect) or **SAML 2.0** — and then lets the org's users log into the React admin UI by bouncing through their corporate IdP. The package owns four distinct responsibilities that are worth separating in your head:
>
> 1. **Provider administration** — a tenant-scoped REST CRUD surface (`SsoController` → `SsoService`) for creating, listing, deleting, and connectivity-testing providers, persisted as `SsoProvider` rows whose secrets are AES-GCM encrypted and whose admin-supplied URLs are run through an SSRF chokepoint.
> 2. **SSRF defense** — `UrlGuard`, a self-contained, fail-closed validator + pinned HTTP fetcher that vets every admin-supplied issuer/metadata URL before the server ever connects to it.
> 3. **Spring Security wiring** — `SsoSecurityConfig` reads the enabled providers out of the database at startup and synthesizes Spring's `ClientRegistrationRepository` (OIDC) and `RelyingPartyRegistrationRepository` (SAML) so the framework's `oauth2Login` / `saml2Login` machinery knows where to send the browser.
> 4. **The login landing** — `SsoLoginController` kicks off a login; after the IdP returns, `SsoSuccessHandler` runs the security gates (email present, email verified, domain allow-listed, identity bound) and delegates the durable writes to `SsoProvisioningService`, which performs **JIT (just-in-time) provisioning** of the local user, the `(provider, subject)` identity binding, and org membership — all in one transaction — then mints a `cp_session` cookie.
>
> The recurring design theme across the whole package is **defense against a hostile or compromised IdP and against cross-tenant abuse**: identities are keyed on the stable `(provider, subject)` pair (never the mutable email); JIT is denied by default unless the email domain is explicitly allow-listed; unverified OIDC emails are refused; admin URLs are SSRF-vetted; and every provider read/write/delete is scoped by `(id, orgId)` so an admin of org A can never touch org B's provider.

## How it fits the bigger picture

The control panel issues offline-verifiable Ed25519 license files to customer Docker apps. SSO is purely about **who can administer the control panel itself** — it authenticates humans into the admin plane, it does **not** sign licenses or touch the license verifier SDK. Its main external collaborators:

- **`com.example.cp.auth.SecurityConfig`** declares **two** Spring Security filter chains. The `@Order(1)` `ssoFilterChain` is scoped to the OAuth2/SAML redirect + callback paths and installs `oauth2Login`/`saml2Login` with `SsoSuccessHandler` as the success handler. The `@Order(2)` chain is the **stateless** `/api/**` chain whose entry point returns a 401 JSON ProblemDetail (never a 302 to an IdP). After SSO succeeds, the handler mints a `cp_session` JWT cookie that the API chain's `JwtAuthFilter` accepts as a bearer token — that cookie is the bridge from the stateful browser SSO flow into the stateless API.
- **`com.example.cp.auth.SessionTokenService`** mints the HS256 `cp_session` token (`purpose=session`, default 30 min TTL) the handler drops as a cookie.
- **`com.example.cp.rbac.AuthoritiesLoader`** computes the authority set baked into that session token.
- **`com.example.cp.keys.KeyEncryptor`** AES-GCM-encrypts the OIDC client secret (versioned KEK envelope) so plaintext never lands in `config_json` or any DTO.
- **`com.example.cp.audit.AuditWriter` / `AuditContext`** record every consequential SSO event — provider created/deleted/tested, and login outcomes (success and each distinct denial reason).
- **`com.example.cp.users` / `com.example.cp.orgs`** supply the `User`, `OrgMember`, and their repositories that JIT provisioning writes into.
- **`com.example.cp.common.TrustedProxyResolver`** resolves the real client IP (behind proxies) for audit rows.

---

## File-by-file

### `SsoProvider.java` — the persisted provider entity

**Responsibility.** JPA entity mapping the `sso_providers` table; one row = one configured IdP for one org.

**Type:** `public class SsoProvider` (Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`).

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | Assigned by the service via `Ids.newId()`, not DB-generated. This id is what's embedded into the Spring registration id (`oidc-<id>` / `saml-<id>`). |
| `UUID orgId` | `org_id` | The owning tenant. Every admin path filters on this. |
| `Type type` | `type` (`@Enumerated(STRING)`, len 20) | `SAML` or `OIDC`. |
| `String configJson` | `config_json` (`jsonb`, `@JdbcTypeCode(SqlTypes.JSON)`) | The provider config as JSON. For OIDC: `issuer`, `clientId`, optional `authorizationUri`/`tokenUri`/`userInfoUri`/`jwkSetUri`, `allowedEmailDomains`. For SAML: `metadataUrl`, `allowedEmailDomains`. **The plaintext `clientSecret` is stripped before persist** (see `SsoService.create`). |
| `byte[] clientSecretEnc` | `client_secret_enc` (nullable) | AES-GCM blob of the OIDC client secret produced by `KeyEncryptor`. Never returned in a DTO. |
| `String allowedEmailDomains` | `allowed_email_domains` (nullable) | CSV of verified domains permitted for JIT. **Null/blank = deny all JIT/auto-link** (fail-closed). |
| `OffsetDateTime createdAt` | `created_at` | Set by the service. |

**Nested type:** `public enum Type { SAML, OIDC }`.

**Gotchas.**
- The `clientSecretEnc` column exists but, in the current code, it is **written** (by `SsoService.create`) yet **not read back at login time** — `SsoSecurityConfig.clientRegistrationRepository()` builds the OIDC `ClientRegistration` from `cfg.getOrDefault("clientSecret", "")`, i.e. from `config_json` (where the secret has already been stripped). So a freshly created OIDC provider effectively has an empty client secret at runtime unless the IdP supports public/no-secret clients. Treat this as a known seam (encryption-at-rest is in place; runtime decrypt-and-inject is not wired). A new engineer must not assume `clientSecretEnc` flows into the live registration.
- `allowedEmailDomains` is parsed out of the incoming `config` map in `SsoService.create` and stored both as its own column **and** left inside `config_json` (it is not stripped). The login-time gate reads the **column** via `provider.getAllowedEmailDomains()`.

---

### `SsoIdentity.java` — the `(provider, subject) → user` binding

**Responsibility.** JPA entity for the `sso_identities` table. This is the **anti-account-takeover keystone** of the whole package.

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | `Ids.newId()`. |
| `UUID providerId` | `provider_id` | FK-ish to `SsoProvider.id`. |
| `String subject` | `subject` | The stable IdP subject — OIDC `sub`, SAML `NameID`. **Never the email.** |
| `UUID userId` | `user_id` | The local `User` this external identity is bound to. |
| `OffsetDateTime createdAt` | `created_at` | |

**Why it exists (read the class Javadoc).** A local user is identified by email, but a *hostile or misconfigured IdP can change the email it asserts for a given subject*. If login resolved purely by email, an attacker who controls an IdP account could assert `victim@corp.com` and take over the victim's local account. By binding `(providerId, subject)` to a `userId` on first login and keying all subsequent lookups on that pair, a later email change by the IdP can never repoint the binding to a different local user. Email is only consulted on the *very first* login for a `(provider, subject)`, and only after the domain allow-list gate passes.

---

### `SsoIdentityRepository.java`

**Responsibility.** Spring Data JPA repo for `SsoIdentity`.

**Methods.**
- `Optional<SsoIdentity> findByProviderIdAndSubject(UUID providerId, String subject)` — the single load-bearing query. Called by both `SsoSuccessHandler` (read-only, to decide whether the new-binding gates apply) and `SsoProvisioningService` (authoritatively, inside the transaction). Returns empty for a first-time `(provider, subject)`, which triggers the JIT/link path.

---

### `SsoProviderRepository.java`

**Responsibility.** Spring Data JPA repo for `SsoProvider`.

**Methods.**
- `List<SsoProvider> findByOrgId(UUID orgId)` — tenant-scoped listing; used by `SsoLoginController.start` and `SsoService.listForOrg`.
- `List<SsoProvider> findByEnabledTrue()` — used **only by `SsoSecurityConfig`** at bean-build time to enumerate every enabled provider across all tenants and synthesize the framework registrations. (Cross-tenant by design — the registration ids embed the provider id so per-tenant scoping happens later, at the provider/subject layer.)
- `Optional<SsoProvider> findByIdAndOrgId(UUID id, UUID orgId)` — **the IDOR guard.** A provider id that belongs to another org (or doesn't exist) returns empty, letting `delete`/`test` 404 instead of mutating/probing a cross-tenant resource. Mirrors `WebhookSubscriptionRepository.findByIdAndOrgId`.

---

### `SsoController.java` — tenant-scoped provider admin REST surface

**Responsibility.** `@RestController` at `/api/v1/orgs/{orgId}/sso`. Thin controller; all logic lives in `SsoService`. Lives on the stateless `/api/**` chain, so callers authenticate with a session JWT or API key.

**Authorization model.** Every method is guarded by `@PreAuthorize("hasAuthority('sso.read'|'sso.write') or @orgAccess.isOwnerOrAdmin(#orgId)")`. So a caller passes if they either hold the global RBAC authority **or** are an OWNER/ADMIN of the path org (`OrgAccessChecker.isOwnerOrAdmin` returns true for super-admins or org members ranked ≥ ADMIN).

| Endpoint | Method | Authority | Delegates to | Notes |
|---|---|---|---|---|
| `GET /` | `list(orgId)` → `List<SsoDto>` | `sso.read` or org admin | `service.listForOrg(orgId)` | Maps each entity through `SsoDto.from`. |
| `POST /` | `create(orgId, CreateRequest)` → `SsoDto` | `sso.write` or org admin | `service.create(orgId, type, config)` | Body validated with `@Valid`. |
| `DELETE /{id}` | `delete(orgId, id)` → 204 | `sso.write` or org admin | `service.delete(id, orgId)` | Scoped by `(id, orgId)` — proving you manage the *path* org is not enough; the resource must also belong to it, else org A's admin could delete org B's provider by id. |
| `POST /{id}/test` | `test(orgId, id)` → `SsoService.TestResult` | `sso.write` or org admin | `service.test(id, orgId)` | Same `(id, orgId)` scoping; performs the SSRF-pinned connectivity probe. |

**Records (request/response shapes).**
- `CreateRequest(@NotNull SsoProvider.Type type, @NotNull Map<String,Object> config)` — the raw config map is passed straight to the service, which validates/strips it.
- `SsoDto(UUID id, UUID orgId, Type type, String config, boolean enabled, OffsetDateTime createdAt)` with `static from(SsoProvider)`. **Note `config` here is the *post-strip* `configJson`** — the client secret has already been removed, and `clientSecretEnc` is never exposed.

**Gotcha.** `create`/`delete` are write operations but there is no `@PostMapping` idempotency annotation here; the admin UI is expected to drive these directly. Auditing is handled inside the service (fail-closed), not via the controller.

---

### `SsoService.java` — provider lifecycle + SSRF-gated create/test

**Responsibility.** `@Service` owning the business logic for provider CRUD: validation, SSRF vetting of URLs, client-secret encryption, fail-closed auditing, and the connectivity `test`.

**Collaborators:** `SsoProviderRepository`, `UrlGuard`, `KeyEncryptor`, `AuditWriter`, a private `ObjectMapper`. Reads audit actor/IP from `AuditContext`/`SecurityUtils`.

**Methods.**

`listForOrg(UUID orgId)` *(@Transactional readOnly)* — straight `repo.findByOrgId`.

`create(UUID orgId, Type type, Map<String,Object> config)` *(@Transactional)* — the careful path. Step by step:
1. Reject null type / null-or-empty config (`ApiException.badRequest`).
2. Copy `config` into a **mutable** `HashMap` so the plaintext secret can be stripped.
3. Pull the IdP URL: `issuer` for OIDC, `metadataUrl` for SAML; reject if blank.
4. **`urlGuard.validate(url)`** — the SSRF chokepoint, run *before any DB write*. On `SsrfException`, log the **internal** detail at WARN and throw `ApiException.badRequest(e.publicMessage())` — the caller only ever sees the generic public message.
5. For OIDC, if a non-blank `clientSecret` is present, `keyEncryptor.encrypt(secret.getBytes(UTF_8))` → `clientSecretEnc`. **Then `cfg.remove("clientSecret")` unconditionally** so no plaintext key survives in `config_json`.
6. Extract `allowedEmailDomains` (kept in config too).
7. Serialize `cfg` to JSON; build the `SsoProvider` (`enabled=true`, `createdAt=now`) and `repo.save`.
8. **Fail-closed audit:** `auditWriter.record(..., "sso.provider.created", ..., failClosed=true)` runs the INSERT *inline in this same transaction* (no `REQUIRES_NEW`), so the provider row and its audit row commit atomically — a forensic gap is impossible. Then `AuditContext.markRecorded()` stops the `AuditInterceptor` aspect from writing a duplicate row for the request.

`delete(UUID id, UUID orgId)` *(@Transactional)* — `repo.findByIdAndOrgId(id, orgId)` → 404 if absent/cross-tenant; `repo.delete(p)`; fail-closed `sso.provider.deleted` audit + `markRecorded()`. Same atomic-audit rationale as `create`.

`test(UUID id, UUID orgId)` *(@Transactional readOnly)* → `TestResult` — connectivity probe.
- Loads via `findByIdAndOrgId` (404 if absent/cross-tenant).
- Sets `AuditContext` action `sso.provider.tested` + target so the **aspect** writes the audit row for this read-only path (note: unlike create/delete, `test` does *not* call `markRecorded()`; it sets context and lets the interceptor record, attaching `ok` to the payload).
- Computes the probe URL: OIDC → `<issuer>/.well-known/openid-configuration` (handles trailing slash); SAML → the raw `metadataUrl`.
- **`urlGuard.fetchPinned(url)`** — re-validates *and* GETs with redirects disabled and tight timeouts. `ok = 200..299`.
- Exception handling is deliberately layered: `SsrfException` → log internal detail, surface `e.publicMessage()`; any **other** exception → log `e.toString()` server-side but return the fixed string `"Could not test the identity provider"` (explicitly a regression guard against an old bug that leaked internal exception text to the caller).
- Always `AuditContext.putPayload("ok", result.ok())`.

**Helpers.** `actorUserId()` prefers `AuditContext.currentActorUserId()` (set by `JwtAuthFilter`) then falls back to `SecurityUtils.currentUser()`; `currentIp()` reads `AuditContext.currentIp()`; `asString(Object)` null-safe `toString`.

**Record:** `TestResult(boolean ok, String message)`.

**Why the two audit modes differ.** `create`/`delete` mutate durable state, so their audit rows must be coupled to the same commit (fail-closed, inline). `test` mutates nothing, so a best-effort interceptor-written row is fine.

---

### `UrlGuard.java` — the SSRF chokepoint

**Responsibility.** `@Component`. The single, central defense against **Server-Side Request Forgery** for every admin-supplied IdP URL. Two public entry points: `validate` (parse + policy, no network) and `fetchPinned` (validate + pinned GET). The whole point is that an org admin can type *any* URL into the provider config, and the server must never be tricked into connecting to internal infrastructure (cloud metadata, localhost services, RFC1918 hosts, etc.).

**Config (constructor `@Value`s).**
- `app.sso.url-guard.allow-http` (default `false`) — when false, only `https` is allowed.
- `app.sso.url-guard.allowed-ports` (CSV, default empty) — extra allowed ports. The set always contains `443`; `80` is added iff `allowHttp`. Non-numeric tokens are silently skipped (fail-closed: they just aren't added).

**Constants.** `MAX_BODY_BYTES = 256 KiB`, `CONNECT_TIMEOUT = 2s`, `READ_TIMEOUT = 3s`.

**Public methods.**

`URI validate(String rawUrl)` — runs `resolveAndCheck` and returns the parsed `URI`. No fetch. Used by the **create** path (validate-only).

`FetchResult fetchPinned(String rawUrl)` — re-runs `resolveAndCheck` (so the policy is re-applied immediately before connecting — see TOCTOU note below), then a JDK `HttpClient` GET with **`followRedirects(NEVER)`** (a redirect to `http://169.254.169.254/...` would otherwise re-open the SSRF hole), the short timeouts, and the body capped at 256 KiB before being decoded UTF-8 into `bodySnippet`. **Non-2xx responses are returned, not thrown**, so the test path can map them to `ok=false`. `IOException`/`InterruptedException` → generic `SsrfException("Could not reach the identity provider", …)` (and the interrupt flag is restored). Used by the **test** path.

**Private validation pipeline `resolveAndCheck(String)`** — the heart of it, in order:
1. Reject null/blank.
2. Parse with `new URI(...)` then force `uri.toURL()` to surface malformations consistently → `SsrfException("Invalid URL", …)`.
3. Scheme must be `https`, or `http` only if `allowHttp`.
4. **Reject any URL containing userinfo** (`user:pass@host`) — a classic parser-confusion SSRF vector.
5. Host must be present.
6. Resolve effective port (default 443/80) and require it to be in `allowedPorts`.
7. `InetAddress.getAllByName(host)` — resolve **all** A/AAAA records; reject if none.
8. For **every** resolved address, reject if `isDisallowed(addr)`. Checking *all* addresses (not just the first) defeats DNS records that return one public and one private IP.

**The deny policy — `isDisallowed` / `isAddrPrivate` / `unwrapMapped`.** This is an **allow-list-of-deny, fail-closed** design: anything not positively classifiable as routable-public is rejected.
- `unwrapMapped` first un-nests IPv4-mapped (`::ffff:a.b.c.d`) and IPv4-compatible (`::a.b.c.d`) IPv6 forms so the IPv4 policy applies to the embedded address — otherwise `::ffff:127.0.0.1` would sneak past the IPv4 checks. It is careful **not** to treat `::1`/`::` (compat form `0.0.0.x`) as a bogus IPv4 and instead leaves those to the IPv6 zero-check.
- `isDisallowed` rejects loopback, any-local, link-local, site-local, and multicast via `InetAddress` flags, then defers to `isAddrPrivate`.
- `isAddrPrivate` covers the ranges the JDK flags **miss**: IPv4 `0.0.0.0/8`, `10/8`, **`100.64/10` CGNAT**, `127/8`, **`169.254/16` (incl. the `169.254.169.254` cloud-metadata endpoint)**, `172.16/12`, `192.168/16`; IPv6 `fc00::/7` ULA and `::`/`::1`. Unknown address families **fail closed** (return `true`).

**TOCTOU caveat (documented in the class Javadoc, must-know).** `validate` resolves and vets DNS, but the JDK `HttpClient` **re-resolves** the hostname at request time. True per-connection IP pinning over TLS is hard, so the MVP simply re-runs the full resolve+policy check immediately before the request in `fetchPinned`. A narrow residual DNS-rebinding window remains and is **explicitly accepted** because only super_admin / org_admin can create providers.

**Records / nested types.**
- `record FetchResult(int status, String bodySnippet)` — raw status (no redirect following) + capped body.
- `record Resolved(URI uri, InetAddress[] addresses)` (private).
- `static class SsrfException extends RuntimeException` — carries `publicMessage()` (generic, safe to return to the admin) and `internalDetail()` (the real reason; **WARN-logged server-side only, never returned**). This split is the contract that lets `SsoService` log the precise reason while only echoing the generic message to the caller.

**Diagnostics.** Package-private `allowedPorts()` / `allowHttp()` accessors exist for tests. A `@SuppressWarnings("unused") dump(InetAddress[])` helper is kept for readability/future bulk ops.

---

### `SsoSecurityConfig.java` — synthesizing Spring's registration repositories from the DB

**Responsibility.** `@Configuration`, conditional on `app.sso.enabled` (`havingValue=true`, `matchIfMissing=true` → **on by default**). At context-build time it reads enabled providers from the DB and produces the two beans Spring Security's login machinery requires.

**Config.** `app.sso.base-url` (default `http://localhost:8080`) — the public base URL used to compute redirect/ACS/entity URIs.

**Beans.**

`ClientRegistrationRepository clientRegistrationRepository()` — for each enabled **OIDC** provider:
- `readConfig(p)` parses `config_json` (returns `{}` on parse failure or null).
- Pulls `issuer`, `clientId` (skip if either missing), `clientSecret` (`getOrDefault(..., "")`).
- Registration id = **`"oidc-" + p.getId()`** — this is how the success handler later maps the authentication back to the `SsoProvider`.
- Derives endpoint URIs from the issuer if not explicitly configured: `<iss>/auth`, `/token`, `/userinfo`, `/jwks` (trailing slash on the issuer is stripped first).
- Builds a `ClientRegistration` with `CLIENT_SECRET_BASIC`, `AUTHORIZATION_CODE`, redirect `…/login/oauth2/code/{registrationId}`, scopes `openid profile email`, and `userNameAttributeName("email")`.
- The whole loop is wrapped in try/catch that only **WARN-logs** on failure — a bad DB or one bad provider must not block app startup.
- **If no OIDC registrations were built, a `"placeholder"` registration pointing at `https://invalid.local` is added.** This is a framework requirement: `oauth2Login` cannot be configured against an empty `ClientRegistrationRepository`. The placeholder can never authenticate.

`RelyingPartyRegistrationRepository relyingPartyRegistrationRepository()` — for each enabled **SAML** provider:
- Reads `metadataUrl` (skip if missing).
- Registration id = **`"saml-" + p.getId()`**.
- `RelyingPartyRegistrations.fromMetadataLocation(metadataUrl)` (loads + parses the IdP metadata), with entity id `…/saml2/service-provider-metadata/{registrationId}` and ACS `…/login/saml2/sso/{registrationId}`.
- Per-provider failures are WARN-logged and skipped; if **no** RP registrations result, returns a private **`EmptyRelyingPartyRegistrationRepository`** (implements both the repo interface returning `null` and `Iterable` returning an empty iterator) — unlike OIDC, SAML tolerates an empty repo, so no placeholder is needed.

**Gotchas a new engineer must know.**
- **Registrations are read once, at bean construction.** Creating or deleting a provider via `SsoService` does **not** refresh these in-memory repositories — the app must be restarted (or the beans rebuilt) for a new provider to become loginable. This is the biggest operational surprise in the package.
- `fromMetadataLocation` performs a **network fetch of the SAML metadata at startup**, and it is **not** routed through `UrlGuard`. `metadataUrl` is SSRF-vetted by `SsoService.validate` at *create* time, but the startup fetch here trusts the stored value. (The create-time gate is the mitigation.)
- The OIDC client secret used here comes from `config_json` (already stripped) — see the `SsoProvider` gotcha about `clientSecretEnc` not being wired into the live registration.

---

### `SsoLoginController.java` — start the login

**Responsibility.** `@RestController` at `/api/v1/auth/sso`. The single browser-facing entry point that begins an SSO login by 302-redirecting to the framework's internal authorization endpoint.

**Config.** `app.ui.base-url` (default `http://localhost:5173`) — injected but, notably, **unused** in this class (held for symmetry with the success handler).

**Method.** `RedirectView start(@PathVariable orgSlug, @RequestParam(required=false) String provider)`:
1. `orgRepo.findBySlug(orgSlug)` → 404 if unknown.
2. List that org's **enabled** providers; 400 if none.
3. If `provider` is supplied, match it by **either the provider UUID string or the type name** (`OIDC`/`SAML`, case-insensitive) → 404 if no match. Otherwise pick the first.
4. Compute the registration id (`oidc-<id>` / `saml-<id>`) and redirect to `/oauth2/authorization/<regId>` (OIDC) or `/saml2/authenticate/<regId>` (SAML) — those paths are exactly the ones the `@Order(1)` `ssoFilterChain` matches, so Spring Security takes over and bounces the browser to the IdP.

**Gotcha — the orgId is dropped here.** This endpoint resolves an org but **does not stash it** into the session as `sso.orgId`. The success handler reads `sso.orgId` from the session to decide org membership, but **nothing in the codebase ever writes that attribute** (verified by a repo-wide search). Consequence: at provisioning time `orgId` is effectively always `null`, so `SsoProvisioningService` **skips org membership creation** and the login/audit rows carry a null org. A new engineer wiring up real org-scoped SSO must add the `session.setAttribute("sso.orgId", org.getId().toString())` here (and likely thread it through the OAuth2 `state`, since the IdP round-trip and the callback may not share the same session). Treat this as a known incomplete seam.

---

### `SsoSuccessHandler.java` — post-IdP gating, JIT trigger, session minting

**Responsibility.** `@Component implements AuthenticationSuccessHandler`. Runs **after** the IdP has authenticated the user and Spring has built an `Authentication`. It performs the HTTP-layer security gates, delegates durable writes to `SsoProvisioningService`, mints the `cp_session` cookie, and redirects back to the UI. This is the security-critical join point between "the IdP says who you are" and "you now have a control-panel session."

**Wired in by** `SecurityConfig.ssoFilterChain` as the success handler for both `oauth2Login` and `saml2Login`.

**Collaborators:** `SsoProviderRepository`, `SsoIdentityRepository`, `SsoProvisioningService`, `AuditWriter`, `TrustedProxyResolver`, `SessionTokenService`, `AuthoritiesLoader`.

**Config.** `app.ui.base-url` (default `http://localhost:5173`) — redirect target; `app.auth.cookie-secure` (default `true`) — whether `cp_session` carries `Secure`.

**Constant.** `public static final String SESSION_COOKIE = "cp_session"` — shared with `JwtAuthFilter`, which accepts this cookie as the bearer token.

**`onAuthenticationSuccess(request, response, authentication)` — the control flow** (each denial redirects to a distinct `?sso=…` query so the UI can show a specific message, and each writes a `DENIED` audit row):

```
extract email, name, orgId(from session — see gotcha), client IP (TrustedProxyResolver)
resolve provider (from registration id) and subject (sub / NameID)

GATE 1  email == null            -> audit DENIED "missing-email"        -> redirect uiBaseUrl
GATE 2  email_verified == false  -> audit DENIED "email-not-verified"   -> redirect ?sso=unverified   (OIDC only)
        (read existing (provider,subject) binding to decide if new-binding gates apply)
if NOT already bound:
  GATE 3  provider == null       -> audit DENIED "unknown-provider"     -> redirect ?sso=error
  GATE 4  domain not allow-listed-> audit DENIED "email-domain-not-allowed" -> redirect ?sso=domain
PROVISION (transactional, in SsoProvisioningService)
  on RuntimeException             -> audit DENIED "provisioning-failed"  -> redirect ?sso=error
issue cp_session cookie; redirect ?sso=success
```

Key points and the *why* behind each gate:
- **GATE 1 / email extraction** (`extractEmail`): OIDC reads the `email` attribute (falling back to the principal name); SAML reads `email` → `mail` → NameID via `firstNonNull`. No email ⇒ can't provision.
- **GATE 2** (`isEmailExplicitlyUnverified`): returns true **only** when an OIDC `email_verified` claim is literally `false` (Boolean or `"false"` string). An **absent** claim does **not** block (SAML has no such claim). This is the account-takeover guard (finding #47): a careless IdP that lets users self-assert unverified emails must not be able to mint/hijack a local account keyed on that email.
- **The "already bound" read** (`identityRepo.findByProviderIdAndSubject`): purely to decide whether GATES 3–4 apply. If the `(provider, subject)` is already bound to a user, the domain allow-list and provider-known gates are **skipped** — a returning user keeps logging in even if the org admin later tightens the domain list. The authoritative resolution still happens transactionally in the provisioning service.
- **GATE 3** (`resolveProvider`): maps the authentication's registration id (`oidc-<uuid>` / `saml-<uuid>`) back to an `SsoProvider`. For OIDC it reads `OAuth2AuthenticationToken.getAuthorizedClientRegistrationId()`; for SAML it reads `Saml2AuthenticatedPrincipal.getRelyingPartyRegistrationId()`. It splits on the first `-`, parses the trailing UUID, and looks up the provider. Returns null on any malformation. A null provider on a *new* identity is fatal because we can't evaluate the domain allow-list.
- **GATE 4** (`emailDomainAllowed`): case-insensitive exact match of the email's domain against the provider's `allowedEmailDomains` CSV. **Null/blank list ⇒ deny** (matches the `SsoProvider` doc: deny JIT by default). Also rejects malformed emails (no `@`, or `@` at the end).
- **PROVISION:** calls `provisioningService.provision(...)` passing a **masked** email for audit payloads (never the raw value). Any `RuntimeException` is caught, logged (no partial state — the provisioning is one transaction), audited as `provisioning-failed`, and the user is redirected to `?sso=error`.

**`issueSessionCookie(response, user)`** — the bridge into the stateless API:
- `superAdmin = user.isSuperAdmin()`.
- `authoritiesLoader.authoritiesFor(user.getId(), null, superAdmin)` — note `orgId=null` here, so authorities are the user's global/cross-org set (super-admins get all permissions + `SUPER_ADMIN`).
- `sessionTokenService.issue(userId, email, superAdmin, authorities, user.getTokenVersion())` → HS256 `cp_session` JWT (`tokenVersion` lets server-side revocation invalidate the cookie).
- Sets the cookie **HttpOnly**, `Secure` per `cookieSecure`, `Path=/`, max-age = the session TTL (clamped to `Integer.MAX_VALUE`), and **`SameSite=Lax`** (Lax, not Strict, so the cookie survives the top-level redirect back from the IdP).

**Subject / claim extraction helpers.**
- `extractSubject`: OIDC `OidcUser.getSubject()` → generic `OAuth2User` `sub` attribute → `getName()`; SAML `Saml2AuthenticatedPrincipal.getName()` (the NameID). **Deliberately never the email** — the subject is the stable identity key.
- `extractName`: OIDC `name` attribute; SAML `displayName` → `name`.
- `mask(email)`: keeps the first char + domain (`a***@corp.com`), or `***@corp.com` for very short locals — so audit logs never store raw addresses.
- `extractOrgId(request)`: reads `sso.orgId` from the **HTTP session** (string → UUID, null-safe). **See the `SsoLoginController` gotcha — nothing writes this attribute, so it's effectively always null today.**
- `firstNonNull(T...)`: `@SafeVarargs` first-non-null helper.

**Gotcha.** Because authorities are loaded with `orgId=null`, the minted session reflects only global/super-admin authorities, not org-scoped membership roles. Combined with the unwritten `sso.orgId`, org-scoped behavior over SSO is currently a stub.

---

### `SsoProvisioningService.java` — transactional JIT provisioning

**Responsibility.** `@Service` extracted from `SsoSuccessHandler` so the user/identity/membership writes (and their audit rows) commit as **one transaction**. The class Javadoc spells out *why* this seam exists: before it, the handler was a transaction-script where each `save` committed independently, so a failure mid-sequence left **partially-provisioned state** (a user with no identity binding, or an identity with no membership). Wrapping the writes here in `@Transactional` makes provisioning **all-or-nothing**.

**Division of labor (must-know).** HTTP concerns — provider/subject/email extraction, the email-verified and domain allow-list gates, cookie minting, redirects — **stay in the handler**. Only the durable writes live here. By the time `provision` is called, the caller has already enforced the gates, so `(email, domain)` is trusted to JIT/link.

**Collaborators:** `UserRepository`, `OrgMemberRepository`, `SsoIdentityRepository`, `AuditWriter`.

**Record:** `ProvisionResult(User user)`.

**`provision(SsoProvider provider, String subject, String email, String name, UUID orgId, String maskedEmail, String ip)` *(@Transactional)*** — ordered logic:

1. **Strong binding first.** If `subject != null`, `identityRepo.findByProviderIdAndSubject(provider.getId(), subject)`; if present, load that bound `User` by id. **An existing `(provider, subject)` wins regardless of the asserted email** — the anti-hijack invariant.
2. **First login for this `(provider, subject)`** (user still null): `userRepo.findByEmail(email)`. If absent, JIT-create via `jitCreateUser` and audit `user.created` with `via=sso` and the **masked** email. If present, **link the existing user** — never elevate: `jitCreateUser` always sets `superAdmin=false`, and an existing user keeps its current flags (we only link).
3. **Persist the binding.** If `subject != null`, save a new `SsoIdentity(provider, subject, user)` so future logins skip the email path, and audit `sso.identity.linked` (scoped to `provider.getOrgId()`).
4. **Ensure org membership (idempotent).** If `orgId != null` and `ensureMembership` created a new row, audit `org.member.added` with role `MEMBER`.
5. **Login audit.** Always record `sso.login` SUCCESS with the masked email. Because this is the same transaction as steps 1–4, the success row commits atomically with the provisioning.

All five audit calls use `failClosed=false` (best-effort, `REQUIRES_NEW`) — *but* because they execute inside this `@Transactional` method and the method's own writes are what matter, the rollback semantics are driven by the user/identity/membership saves, not the audit rows.

**Private helpers.**
- `jitCreateUser(email, name)` — builds an `ACTIVE`, `superAdmin=false` `User` with `Ids.newId()` and saves it. The **only** SSO path that creates a user; deliberately bypasses `UserService.createUser` (hence the explicit audit in step 2).
- `ensureMembership(orgId, userId)` — returns false if `memberRepo.findByOrgIdAndUserId` already exists; else saves an `OrgMember` with role `MEMBER` and returns true. Idempotent so a returning user isn't re-added.

**Edge cases / gotchas.**
- If `subject` is null (some SAML assertions), **no identity binding is created** — every login then falls back to the email path and re-resolves by email. Combined with the gates in the handler, this is acceptable but means such providers never get the strong binding.
- New JIT users are created **without any password / status workflow** — they're immediately `ACTIVE`. The security boundary is the IdP plus the domain allow-list; there is no second factor at provisioning time.
- Because `orgId` is effectively always null today (see the controller gotcha), step 4 is currently dead in practice and SSO users land with no org membership.

---

## Cross-cutting flows (the two journeys end-to-end)

**A) Admin registers + tests a provider**
```
SsoController.create  --PreAuthorize sso.write|orgAdmin-->  SsoService.create
   -> UrlGuard.validate(issuer|metadataUrl)            (SSRF gate, no fetch)
   -> KeyEncryptor.encrypt(clientSecret) + strip plaintext
   -> repo.save(SsoProvider, enabled=true)
   -> AuditWriter.record("sso.provider.created", failClosed=true) + markRecorded
SsoController.test    -->  SsoService.test
   -> UrlGuard.fetchPinned(<issuer>/.well-known/openid-configuration | metadataUrl)
   -> TestResult(ok = 2xx)   (AuditContext "sso.provider.tested" -> aspect writes row)
```
*(A restart is required before the new provider's registration appears in `SsoSecurityConfig`'s in-memory repos.)*

**B) A user logs in via SSO**
```
Browser -> SsoLoginController.start(orgSlug)  -> 302 /oauth2/authorization/oidc-<id> (or /saml2/authenticate/saml-<id>)
   -> ssoFilterChain (Order 1) redirects to IdP -> user authenticates -> IdP callback
   -> Spring builds Authentication -> SsoSuccessHandler.onAuthenticationSuccess
        gates: email present? email_verified? (if new identity) provider known? domain allow-listed?
        -> SsoProvisioningService.provision (ONE tx): resolve-by-binding | link-by-email | JIT-create;
           save SsoIdentity; ensure OrgMember; audit user.created/identity.linked/member.added/sso.login
        -> issueSessionCookie: AuthoritiesLoader -> SessionTokenService.issue -> Set-Cookie cp_session (HttpOnly,Secure,SameSite=Lax)
        -> 302 uiBaseUrl?sso=success
   -> subsequent /api/** calls: JwtAuthFilter reads cp_session cookie as bearer
```

## Quick gotcha checklist for a new engineer

- **Provider registrations are loaded once at startup** (`SsoSecurityConfig`) — create/delete needs a restart to take effect.
- **`sso.orgId` session attribute is read but never written** — org membership over SSO is currently a stub; `orgId` is null at provisioning time.
- **`clientSecretEnc` is encrypted/stored but not injected into the live OIDC `ClientRegistration`** — the runtime secret comes from the (stripped) `config_json`.
- **Identities key on `(provider, subject)`, never email** — this is load-bearing for anti-account-takeover; don't "simplify" login to look up by email.
- **JIT is denied unless the email domain is explicitly allow-listed**, and **OIDC `email_verified=false` is refused** — both are deliberate security gates with audit trails.
- **`UrlGuard` is fail-closed and has an accepted residual DNS-rebinding TOCTOU window**, mitigated by the create-time re-validation and the super_admin/org_admin-only creation path; SAML metadata fetched at startup is **not** re-guarded.
- **Two audit modes**: create/delete are fail-closed/inline (`markRecorded` suppresses the aspect duplicate); test is best-effort via the interceptor. Never echo SSRF internal detail to callers — only `publicMessage()`.
