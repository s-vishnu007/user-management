# P0 Remediation — Locked Implementation Contract

This is the **authoritative** contract for the parallel P0 implementation. Every agent MUST
adhere to these exact signatures, names, and schema. Per-theme detail lives in the sibling
`docs/design/<theme>.md` files. **Only edit the files your bucket owns.** The module is compiled
as a whole, so independently-built pieces fit together iff signatures match this contract.

Build/verify locally with: `JAVA_HOME="/c/Program Files/Java/jdk-24" mvn -q -DskipTests install`.
The project targets Java 21; `spring.jpa.hibernate.ddl-auto=validate`, so **every new/changed entity
field MUST have a matching DB column in a migration** or the app/tests fail to start.

---

## 1. File ownership (disjoint buckets)

- **A — security/auth/session core:** `common/AuthenticatedUser.java`, `common/SecurityUtils.java`,
  `common/RateLimitFilter.java` (NEW), `common/GlobalExceptionHandler.java`,
  `auth/SecurityConfig.java`, `auth/JwtAuthFilter.java`, `auth/SessionTokenService.java`,
  `auth/AuthController.java`, `auth/SessionRevocationStore.java` (NEW),
  `auth/RedisSessionRevocationStore.java` (NEW), `auth/InMemorySessionRevocationStore.java` (NEW),
  `auth/SessionRevocationConfig.java` (NEW), `apikeys/ApiKeyAuthFilter.java`,
  `users/User.java`, `users/UserService.java`, `users/UserController.java`,
  migration `db/changelog/changes/13-session-revocation.sql` (NEW).
- **B — rbac/orgs:** `rbac/RbacAuthorizationService.java` (NEW), `rbac/RbacController.java`,
  `orgs/OrgService.java`, `orgs/OrgController.java`,
  migration `db/changelog/changes/13-rbac-permissions.sql` (NEW).
- **C — tenant access + subscriptions + license controller + api-key service/controller:**
  `security/TenantAccessChecker.java` (NEW), `subscriptions/SubscriptionAccess.java`,
  `subscriptions/SubscriptionController.java`, `licenses/LicenseController.java`,
  `apikeys/ApiKeyService.java`, `apikeys/ApiKeyController.java`, `common/ApiException.java`. No migration.
- **D — usage:** `usage/UsageEvent.java`, `usage/UsageIngestController.java`,
  `usage/UsageIngestService.java`, `usage/UsageEventRepository.java`, `usage/UsageQuotaRepository.java`,
  migration `db/changelog/changes/13-usage-integrity.sql` (NEW).
- **E — sso/ssrf:** `sso/UrlGuard.java` (NEW), `sso/SsoService.java`, `sso/SsoProvider.java`,
  `sso/SsoController.java`, `sso/SsoSecurityConfig.java`, `sso/SsoSuccessHandler.java`,
  `sso/SsoLoginController.java`, `sso/SsoIdentity.java` (NEW), `sso/SsoIdentityRepository.java` (NEW),
  migration `db/changelog/changes/13-sso-hardening.sql` (NEW).
- **F — audit core:** `audit/AuditOutcome.java` (NEW), `audit/AuditWriter.java`, `audit/AuditInterceptor.java`,
  `audit/AuditLog.java`, `audit/AuditController.java`, `common/AuditContext.java`,
  `common/TrustedProxyResolver.java` (NEW), `common/AuditProperties.java` (NEW),
  migration `db/changelog/changes/13-audit-outcome.sql` (NEW).
- **K — keys + license server:** `keys/JwsSigner.java` (NEW), `licenses/CrlController.java`,
  `licenses/LicenseIssuer.java`. No migration.
- **G — schema registration + config + infra:** `db/changelog/db.changelog-master.yaml`,
  `application.yml`, `application-test.yml`, `application-dev.yml` (NEW), `admin-ui/nginx.conf`,
  `control-panel-api/Dockerfile`, `docker-compose.yml`, `sample-docker-app/src/main/resources/application.yml`.
  **G does NOT write migration bodies** — each bucket writes its own `13-*.sql`; G only registers them
  in the master changelog in this order: `13-session-revocation.sql`, `13-rbac-permissions.sql`,
  `13-usage-integrity.sql`, `13-sso-hardening.sql`, `13-audit-outcome.sql`.
- **V — verifier lib:** everything under `license-verifier/`.
- **S — verifier starter:** everything under `license-verifier-spring-boot-starter/`.

If you find you need to change a file another bucket owns, DO NOT edit it — rely on the contracted
signature below; the owning bucket implements it.

---

## 2. Shared Java contracts (exact signatures)

### `common/AuthenticatedUser` (owner A) — add api-key org binding, keep a backward-compatible ctor
```java
public record AuthenticatedUser(UUID userId, String email, boolean superAdmin,
        Set<String> authorities, Collection<? extends GrantedAuthority> grantedAuthorities,
        boolean apiKey, UUID apiKeyOrgId) {
    // Backward-compatible 5-arg ctor for human-user principals (apiKey=false, apiKeyOrgId=null):
    public AuthenticatedUser(UUID userId, String email, boolean superAdmin,
            Set<String> authorities, Collection<? extends GrantedAuthority> grantedAuthorities) {
        this(userId, email, superAdmin, authorities, grantedAuthorities, false, null);
    }
    public boolean hasAuthority(String code) {
        return superAdmin || (authorities != null && authorities.contains(code));
    }
    public boolean isApiKey() { return apiKey; }
}
```
- `ApiKeyAuthFilter` (owner A) constructs with the 7-arg form: `apiKey=true, apiKeyOrgId=key.getOrgId()`.
- All other construction sites keep using the 5-arg form (unchanged).

### `common/SecurityUtils` (owner A) — add caller org id helper
```java
public static Optional<UUID> currentOrgId(); // api-key principal -> apiKeyOrgId; else Optional.empty()
```

### `security/TenantAccessChecker` (owner C) — bean `@Component("tenantAccess")`
Resolve the **target resource's** org and authorize against it. Order for every method:
`super_admin -> true`; api-key principal -> `apiKeyOrgId equals target org`; human -> `OrgMember`
membership (reads) or `OWNER/ADMIN` rank (writes). **No global-authority short-circuit.**
```java
boolean canAccessOrg(UUID orgId);
boolean canAdminOrg(UUID orgId);
boolean canReadSubscription(UUID subscriptionId);
boolean canWriteSubscription(UUID subscriptionId);
boolean canIssueForSubscription(UUID subscriptionId);
boolean canDownloadLicense(UUID subscriptionId);
boolean canReadUsage(UUID subscriptionId);
boolean canIngestUsage(UUID subscriptionId);
```
Used by buckets C (subscriptions/licenses) and D (usage) via `@PreAuthorize("@tenantAccess.x(#id)")`.
The api-key org binding is read from `AuthenticatedUser.apiKeyOrgId()`. The required scope/authority
(e.g. `license.issue`) is combined in the SpEL as `hasAuthority('x') and @tenantAccess.canIssueForSubscription(#id)`.

### `auth/SessionTokenService` (owner A)
```java
public record ParsedToken(UUID userId, String email, boolean superAdmin, Set<String> authorities,
        Instant expiresAt, String jti, long tokenVersion) {}
public IssuedToken issue(UUID userId, String email, boolean superAdmin,
        Collection<String> authorities, long tokenVersion); // embeds jti + "tv" (tokenVersion) claims
```
Callers: `AuthController` (A) and `SsoSuccessHandler` (E) pass `user.getTokenVersion()`.

### `auth/SessionRevocationStore` (owner A)
```java
public interface SessionRevocationStore {
    void denylistJti(String jti, java.time.Duration ttl);
    boolean isJtiDenylisted(String jti);
}
```
Redis impl when a `RedisConnectionFactory` bean exists, else in-memory. `JwtAuthFilter` (A) rejects a
token if `isJtiDenylisted(jti)`, if it loads the `User` and `status != ACTIVE`, or if
`parsed.tokenVersion() != user.getTokenVersion()`. Logout (A) denylists the jti for its remaining TTL.
`UserService.suspend/delete` and password reset (A) increment `users.token_version`.

### `users/User` (owner A) — add field; matches migration column `token_version`
```java
@Column(name = "token_version", nullable = false) private long tokenVersion = 0L; // Lombok getter getTokenVersion()
```

### `rbac/RbacAuthorizationService` (owner B) — bean used by `RbacController`
```java
// Throws ApiException.forbidden(...) when the actor may not perform the assignment.
void assertCanAssign(AuthenticatedUser actor, Role targetRole, UUID targetUserId, UUID orgId);
```
Rules: require authority `role.assign`; forbid assigning roles where `is_system=true` (incl. SUPER_ADMIN)
via the API; forbid self-assignment of elevated roles; actor cannot grant any permission code it does
not itself hold (no amplification); global (`orgId==null`) assignment requires platform authority,
org-scoped requires admin on that org. `OrgService.addMember` (B) enforces actor rank >= granted rank
(OWNER requires OWNER). RBAC catalog reads gated by `@PreAuthorize("hasAuthority('rbac.read')")`.

### `audit/AuditOutcome` (owner F)
```java
public enum AuditOutcome { SUCCESS, DENIED, FAILED } // .name() persisted to audit_log.outcome VARCHAR(16)
```

### `audit/AuditWriter` (owner F) — keep existing 7-arg (delegates SUCCESS/non-fail-closed), add overload
```java
public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
        Map<String,Object> payload, String ip);                                  // existing, kept
public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
        Map<String,Object> payload, String ip, AuditOutcome outcome, boolean failClosed); // NEW
```
Callers of the new overload: `GlobalExceptionHandler` (A), `AuthController` (A), `SsoSuccessHandler` (E),
`SsoService` (E), `AuditInterceptor` (F).

### `common/AuditContext` (owner F) — additions used (call-only) by bucket A
```java
public static void setOutcome(AuditOutcome o);
public static AuditOutcome currentOutcome();   // default SUCCESS
public static void markFailClosed();
public static boolean isFailClosed();
```

### `common/TrustedProxyResolver` (owner F) — used by `JwtAuthFilter` (A)
```java
@Component public class TrustedProxyResolver {
    public String resolveClientIp(jakarta.servlet.http.HttpServletRequest req); // honors XFF only from app.audit.trusted-proxies CIDRs
}
```

### License revocation — verifier lib (owner V)
```java
public interface RevocationChecker {                          // RevocationChecker.java
    boolean isRevoked(String jti);
    default boolean isOperational() { return true; }
    static RevocationChecker none() { /* never-revokes, operational */ }
}
public final class RevocationList { /* issuer, issuedAt, nextUpdate, Set<String> revokedJtis;
    boolean isRevoked(String jti); boolean isStale(Instant now, Duration maxStale); */ }
public final class CrlVerifier {                              // verifies the signed CRL JWS against JWKS
    public CrlVerifier(PublicKeyProvider keys, String expectedIssuer);
    public RevocationList verify(String crlJws);              // typ must be "crl+jwt", EdDSA, kid in JWKS
}
public class LicenseRevokedException extends LicenseException { public String getJti(); }
```
`LicenseVerifier` (V) changes: (1) **reject a missing `exp`** (`LicenseExpiredException` or malformed);
(2) builder gains `.revocationChecker(RevocationChecker)` (default `RevocationChecker.none()`); after
temporal/aud/iss checks, if `revocationChecker.isRevoked(jti)` throw `LicenseRevokedException`.

### License revocation — server (owner K)
- `keys/JwsSigner` (NEW): `String sign(JWTClaimsSet claims, String typ, KeyService.ActiveKey active)` —
  EdDSA sign with the active key, header `kid`+`typ`.
- `licenses/CrlController` (K): `GET /api/v1/licenses/crl` returns `200 text/plain` compact JWS
  (`typ=crl+jwt`) with claims `{iss=<app.signing.issuer>, iat, nextUpdate (now+app.signing.crl-ttl),
  revoked:[jti...]}` built from `LicenseTokenRepository` REVOKED rows, signed via `JwsSigner`. Keep the
  legacy `GET /api/v1/licenses/revoked` JSON endpoint too.

### License revocation — starter (owner S)
- `LicenseProperties`: add `crlUrl` (String), `crlRefreshInterval` (Duration=PT15M), `crlMaxStale` (Duration=PT1H).
- `CrlRevocationChecker implements RevocationChecker` (NEW): scheduled fetch of `crlUrl`, verify via
  `CrlVerifier`, cache `RevocationList`; `isRevoked` consults cache; `isOperational()` false when the
  cached list is stale beyond `crlMaxStale` (fail-closed). When `crlUrl` is blank, wire `RevocationChecker.none()`.
- Auto-config wires the `RevocationChecker` into the `LicenseVerifier` builder.
  `RequiresPermissionAspect`/`LicenseService` deny when the current license is revoked (or, fail-closed,
  when the checker is not operational).

---

## 3. DB schema additions (exact — entities MUST match)

Each migration is a Liquibase formatted-SQL file with a `--liquibase formatted sql` header and a
`--changeset cp:<id>` per statement group. Use idempotent guards / `ON CONFLICT DO NOTHING` for seeds.

- **13-session-revocation.sql** (A): `ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;`
- **13-rbac-permissions.sql** (B): insert permissions `role.assign`, `rbac.read` (category `rbac`);
  grant both to role `SUPER_ADMIN`; grant `role.assign` to no org role by default. `ON CONFLICT (code) DO NOTHING`.
- **13-usage-integrity.sql** (D):
  `ALTER TABLE usage_events ADD COLUMN event_id VARCHAR(120);`
  `CREATE UNIQUE INDEX IF NOT EXISTS ux_usage_events_dedup ON usage_events (subscription_id, jti, event_id) WHERE event_id IS NOT NULL;`
  `ALTER TABLE usage_events ADD CONSTRAINT ck_usage_qty_nonneg CHECK (quantity >= 0);`
  insert permissions `usage.read`, `usage.ingest`, `license.read` (category `usage`/`license`); grant to `SUPER_ADMIN`.
  (Entity `UsageEvent` adds `@Column(name="event_id") String eventId;`)
- **13-sso-hardening.sql** (E):
  `ALTER TABLE sso_providers ADD COLUMN allowed_email_domains TEXT;`
  `ALTER TABLE sso_providers ADD COLUMN client_secret_enc BYTEA;`
  `CREATE TABLE sso_identities (id UUID PRIMARY KEY, provider_id UUID NOT NULL REFERENCES sso_providers(id) ON DELETE CASCADE, subject TEXT NOT NULL, user_id UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), CONSTRAINT ux_sso_identity UNIQUE (provider_id, subject));`
  insert permission `sso.read`; grant to `SUPER_ADMIN`.
  (Entities: `SsoProvider` adds `allowedEmailDomains` String + `clientSecretEnc` byte[]; new `SsoIdentity`.)
- **13-audit-outcome.sql** (F): `ALTER TABLE audit_log ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';`
  (Entity `AuditLog` adds `@Column String outcome;`) Do NOT add a DB role-switch migration (would break the
  single-role dev/test setup); document the least-privilege app-role recommendation in a comment only.

Confirm exact existing table/column names by reading the prior migrations in
`control-panel-api/src/main/resources/db/changelog/changes/` before writing DDL.

---

## 4. SecurityConfig public allow-list (owner A)

Replace `.anyRequest().permitAll()` with `.anyRequest().authenticated()`. Public matchers:
`/api/v1/auth/**`, `/api/v1/licenses/revoked`, `/api/v1/licenses/crl` (NEW), `/.well-known/jwks.json`,
`/actuator/health`, `/actuator/health/**`, `/actuator/info`, swagger/api-docs, oauth2/saml2 endpoints.
All other `/actuator/**` and everything else → authenticated. CORS origins from `app.cors.allowed-origins`
(comma-separated). Register `RateLimitFilter` before `JwtAuthFilter` for `/api/v1/auth/**`. Add security
response headers (HSTS, X-Content-Type-Options, X-Frame-Options DENY, a conservative CSP for API responses).

## 5. application.yml keys (owner G) — defaults
```
app.auth.session-ttl: PT30M
app.auth.revocation.enabled: true
app.auth.expose-reset-token: ${APP_AUTH_EXPOSE_RESET_TOKEN:false}   # true only in application-dev.yml
app.cors.allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173}
app.ratelimit.auth.capacity: ${APP_RATELIMIT_AUTH_CAPACITY:10}
app.ratelimit.auth.refill-per-minute: ${APP_RATELIMIT_AUTH_REFILL:10}
app.signing.crl-ttl: ${APP_CRL_TTL:PT1H}
app.usage.occurred-at-max-past: P35D
app.usage.occurred-at-max-future: PT5M
app.usage.enforce-limit: true
app.audit.trusted-proxies: []          # list of CIDR
management.endpoints.web.exposure.include: health,info,metrics,prometheus   # but secure non-health via SecurityConfig
```
`application-test.yml` MUST set `app.auth.session-secret` and `app.signing.master-key` test values and
keep `app.auth.expose-reset-token:false`. `docker-compose.yml`: require injected secrets via
`${VAR:?msg}` for POSTGRES_PASSWORD and APP_KEY_ENC_MASTER, **add `APP_AUTH_SESSION_SECRET: ${APP_AUTH_SESSION_SECRET:?...}`
to the api service**, and remove host port publishing for postgres/redis (keep only the api + ui ports).
