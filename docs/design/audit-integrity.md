# Design spec: audit-integrity

## Shared contracts (other files depend on these — keep signatures exact)

### `AuditOutcome` (enum)
- **File:** `control-panel-api/src/main/java/com/example/cp/audit/AuditOutcome.java`
- **Purpose:** Single source of truth for the outcome dimension written to audit_log.outcome and read back by AuditController DTO. Used by AuditWriter, AuditContext, AuditInterceptor, GlobalExceptionHandler, AuthController, SsoSuccessHandler.
- **Signature/contract:**

```
package com.example.cp.audit; public enum AuditOutcome { SUCCESS, DENIED, FAILED }  // .name() persisted as VARCHAR(16)
```

### `AuditWriter.record (overloaded signature)` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/audit/AuditWriter.java`
- **Purpose:** New canonical record(...) method that accepts an AuditOutcome and a boolean failClosed; the old 7-arg signature is kept as a SUCCESS/fail-open delegate so existing callers (only AuditInterceptor) compile unchanged.
- **Signature/contract:**

```
public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId, Map<String,Object> payload, String ip, AuditOutcome outcome, boolean failClosed)  // when failClosed=true the INSERT runs in caller's tx and exceptions propagate; when false REQUIRES_NEW + swallow. Legacy record(...7 args) delegates with outcome=SUCCESS, failClosed=false.
```

### `AuditContext.setOutcome / currentOutcome` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/common/AuditContext.java`
- **Purpose:** Adds an outcome slot (default SUCCESS) and a failClosed flag to the per-request ThreadLocal so the interceptor and exception handler can record DENIED/FAILED and so high-value actions can opt into fail-closed.
- **Signature/contract:**

```
public static void setOutcome(AuditOutcome o); public static AuditOutcome currentOutcome(); public static void markFailClosed(); public static boolean isFailClosed();
```

### `trustedProxyResolver (TrustedProxyConfig)` (bean)
- **File:** `control-panel-api/src/main/java/com/example/cp/common/TrustedProxyResolver.java`
- **Purpose:** Resolves the real client IP from X-Forwarded-For ONLY when the immediate peer (request.getRemoteAddr) is in the configured trusted-proxy CIDR allowlist; otherwise returns getRemoteAddr(). Replaces the untrusted XFF parsing in AuditInterceptor and the getRemoteAddr-only logic in JwtAuthFilter.
- **Signature/contract:**

```
@Component public class TrustedProxyResolver { public String resolveClientIp(jakarta.servlet.http.HttpServletRequest req); }  bound to app.audit.trusted-proxies (List<String> CIDR, default empty)
```

### `app.audit.trusted-proxies` (config-property)
- **Purpose:** List of CIDR blocks whose XFF header is trusted. Empty (default) => never trust XFF, always use the direct socket peer. Read by TrustedProxyResolver.
- **Signature/contract:**

```
app:\n  audit:\n    trusted-proxies: [] # e.g. ["10.0.0.0/8","172.16.0.0/12"]
```

### `app.audit.fail-closed-actions` (config-property)
- **Purpose:** Allowlist of action codes for which audit writes must be transactional/fail-closed (failure aborts the business tx). Read by AuditInterceptor and the high-value service call sites.
- **Signature/contract:**

```
app:\n  audit:\n    fail-closed-actions: ["license.issued","license.revoked","key.rotated","rbac.role.assigned","rbac.role.removed","org.owner.transferred","apikey.created","apikey.revoked","sso.provider.created","sso.provider.deleted"]
```

### `13-audit-outcome-and-roles.sql` (db-migration)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-audit-outcome-and-roles.sql`
- **Purpose:** Adds audit_log.outcome column (NOT NULL DEFAULT 'SUCCESS'), an index on (action,outcome,occurred_at), creates least-privilege role cp_app with INSERT/SELECT only on audit_log and the runtime grants for other tables, and documents that migrations run as the owner (cp), the app runs as cp_app.
- **Signature/contract:**

```
changesets: cp:13-audit-outcome-add-column ; cp:13-audit-app-role (runWith owner) ; see migration body in fileEdits
```

## File edits

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/audit/AuditOutcome.java`
- Create new enum `package com.example.cp.audit; public enum AuditOutcome { SUCCESS, DENIED, FAILED }`.
- Persisted via .name() as VARCHAR(16). DENIED = authz/authn refusal (AccessDeniedException, AuthenticationException, ApiException 401/403). FAILED = any other thrown exception (4xx validation, 5xx, business ApiException not in 401/403).

### [NEW FILE] `control-panel-api/src/main/resources/db/changelog/changes/13-audit-outcome-and-roles.sql`
- Add `--liquibase formatted sql` header.
- changeset `cp:13-audit-outcome-add-column`: `ALTER TABLE audit_log ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';` then `CREATE INDEX idx_audit_log_action_outcome ON audit_log(action, outcome, occurred_at);` Add a CHECK or rely on app enum — recommend `ALTER TABLE audit_log ADD CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS','DENIED','FAILED'));`. rollback: drop constraint, drop index, drop column.
- changeset `cp:13-audit-app-role` with `runWith` left as default (runs as owner = liquibase user `cp`): create the least-privilege runtime role idempotently. Use splitStatements:false plpgsql DO block: `DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='cp_app') THEN CREATE ROLE cp_app LOGIN PASSWORD 'cp_app'; END IF; END $$;` Then: `REVOKE ALL ON audit_log FROM cp_app; GRANT INSERT, SELECT ON audit_log TO cp_app;` (NO UPDATE/DELETE — DB enforces append-only alongside the existing audit_log_block_modifications triggers). Grant SELECT/INSERT/UPDATE/DELETE on all OTHER application tables: `GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO cp_app; REVOKE UPDATE,DELETE ON audit_log FROM cp_app;` and `GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO cp_app;` Also `GRANT USAGE ON SCHEMA public TO cp_app;` Note: do NOT grant on databasechangelog/databasechangeloglock so the app role cannot run migrations. rollback: `REVOKE ALL ON ALL TABLES IN SCHEMA public FROM cp_app; DROP ROLE IF EXISTS cp_app;`
- Add a leading comment block (ops note) in the file: migrations MUST be applied as the owner role (cp) via Liquibase; the running application MUST connect as cp_app (DB_USER=cp_app) which has only INSERT/SELECT on audit_log and cannot UPDATE/DELETE it nor run DDL. This is the DB-level enforcement of append-only audit independent of the trigger.
- IMPORTANT compile/runtime ordering: this changeset only ADDs a nullable-with-default column, so Hibernate ddl-auto=validate stays green only after AuditLog entity adds the mapped field (see AuditLog.java edit). Ship both in the same change.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/audit/AuditLog.java`
- Add field after occurredAt: `@Column(name = "outcome", nullable = false, length = 16) @Enumerated(EnumType.STRING) private AuditOutcome outcome;` — required because ddl-auto=validate will fail validation against the new NOT NULL column otherwise.
- Add import `jakarta.persistence.Enumerated; jakarta.persistence.EnumType;`. Entity stays @Immutable (no setter exposure needed beyond Lombok @Setter already present).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/common/AuditContext.java`
- Import `com.example.cp.audit.AuditOutcome` (new cross-package dependency from common -> audit; acceptable, common already has no cycle since audit does not import common.AuditContext except via interceptor at runtime — verify no compile cycle: audit.AuditInterceptor imports common.AuditContext, and common.AuditContext would import audit.AuditOutcome; this is a package cycle but NOT a class cycle and Java permits it. To avoid the package cycle entirely, prefer defining AuditOutcome in package com.example.cp.common instead of com.example.cp.audit — RECOMMENDED: put AuditOutcome in com.example.cp.common). Adjust AuditOutcome.java path/package accordingly if chosen.
- Add to inner `Ctx`: `AuditOutcome outcome = AuditOutcome.SUCCESS; boolean failClosed = false;`
- Add methods: `public static void setOutcome(AuditOutcome o){ CTX.get().outcome = o; }` `public static AuditOutcome currentOutcome(){ return CTX.get().outcome; }` `public static void markFailClosed(){ CTX.get().failClosed = true; }` `public static boolean isFailClosed(){ return CTX.get().failClosed; }`
- Note: clear() already removes the ThreadLocal so defaults reset per request.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/audit/AuditWriter.java`
- Change SQL to include outcome column: `INSERT INTO audit_log (id, actor_user_id, actor_org_id, action, target_type, target_id, payload_json, ip_address, occurred_at, outcome) VALUES (?,?,?,?,?,?,?::jsonb,?::inet, now(), ?)` — use DB clock `now()` for occurred_at (DB clock requirement #70) instead of binding `OffsetDateTime.now()`; drop the ps.setObject(9, OffsetDateTime.now()) and bind outcome at the new last position as `ps.setString(9, outcome.name())`.
- Add overloaded canonical method: `public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId, Map<String,Object> payload, String ip, AuditOutcome outcome, boolean failClosed)`. Behavior: serialize payload as today; if `failClosed` then run the INSERT inline (NO try/catch swallow, NO REQUIRES_NEW) so an exception propagates and rolls back the caller tx; if not failClosed keep current REQUIRES_NEW + try/catch swallow.
- Implementation approach to keep transactional semantics: split into two private methods annotated separately: `@Transactional(propagation = Propagation.REQUIRES_NEW) protected void writeNewTx(...)` (fail-open, swallow) and `@Transactional(propagation = Propagation.MANDATORY|REQUIRED) protected void writeSameTx(...)` (fail-closed, propagate). Because self-invocation bypasses Spring AOP proxies, expose these via the bean itself injected as `@Lazy AuditWriter self` OR — simpler and RECOMMENDED — make AuditWriter delegate fail-closed writes through an injected `org.springframework.transaction.support.TransactionTemplate`/`TransactionOperations` is unnecessary; instead, for failClosed=true execute the raw jdbc.update directly WITHOUT REQUIRES_NEW (the call already runs inside the controller/service @Transactional because high-value actions are @Transactional) and let exceptions bubble. For failClosed=false keep the existing @Transactional(REQUIRES_NEW) method. Document that the public record(...failClosed) routes to one of the two paths.
- Keep the legacy 7-arg `record(UUID,UUID,String,String,String,Map,String)` as a thin delegate: `record(actorUserId, actorOrgId, action, targetType, targetId, payload, ip, AuditOutcome.SUCCESS, false);` so AuditInterceptor and any other existing caller compile unchanged.
- Add null-guard: if outcome == null default to SUCCESS.
- Add import for AuditOutcome and remove now-unused `java.time.OffsetDateTime` import if no longer referenced.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/audit/AuditInterceptor.java`
- Inject `TrustedProxyResolver` and a config-bound `Set<String> failClosedActions` (bind via constructor `@Value("${app.audit.fail-closed-actions:}") List<String> ...` or a small @ConfigurationProperties record). Constructor becomes `AuditInterceptor(AuditWriter writer, TrustedProxyResolver proxyResolver, AuditProperties props)`.
- Replace the static `extractIp(HttpServletRequest)` XFF logic to delegate to `proxyResolver.resolveClientIp(req)` (trusted-proxy XFF, #59). Keep `AuditContext.currentIp()` as the override only if set by the filter using the SAME resolver. Remove the unconditional comma-split-trust of XFF.
- Add `@AfterThrowing(pointcut = "inControllerLayer() && mutatingEndpoint()", throwing = "ex")` advice method `afterThrowing(JoinPoint jp, Throwable ex)` that: derives action exactly like afterMutating (reuse a shared private `emit(JoinPoint, AuditOutcome)` helper), maps the exception to an outcome via a new private `AuditOutcome outcomeFor(Throwable ex)` (AccessDeniedException OR AuthenticationException OR ApiException with status 401/403 -> DENIED; everything else -> FAILED), enriches payload with `error.class` and a SHORT `error.message` (truncate, do not log secrets) and the HTTP status if derivable, then writes via the canonical `writer.record(...)` with the computed outcome and failClosed = failClosedActions.contains(action). Wrap in try/finally that ALWAYS calls AuditContext.clear() — and CRITICALLY rethrow nothing (advice must not swallow ex; @AfterThrowing naturally lets ex propagate to GlobalExceptionHandler).
- Refactor afterMutating to set outcome SUCCESS: call `writer.record(userId, orgId, action, targetType, targetId, safePayload, ip, AuditOutcome.SUCCESS, failClosedActions.contains(action))`. For fail-closed SUCCESS actions, because @AfterReturning runs AFTER the controller's @Transactional method has returned (but the tx may still be open until method exit), note the ordering risk (see risks) — for fail-closed actions the audit INSERT must occur inside the still-open business transaction. SAFER design for fail-closed SUCCESS: do the audit write inside the service @Transactional via an explicit AuditWriter call rather than relying on the aspect; the aspect remains the fail-open SUCCESS path and the DENIED/FAILED path. Document this split clearly.
- Extract a private `String deriveAction(JoinPoint, HttpServletRequest)` already exists — reuse for both advices. Add private `outcomeFor` and `emit` helpers. Keep clear() in finally of BOTH advices (currently only afterReturning clears).
- Avoid double-clear races: since JwtAuthFilter ALSO calls AuditContext.clear() in its finally, and the aspect clears too, ordering is filter-after-aspect so it is safe (aspect clears, filter clears again no-op). Keep as-is but document.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/common/GlobalExceptionHandler.java`
- This is the FALLBACK audit path for exceptions thrown OUTSIDE the controller mutating-pointcut (e.g. GET endpoints, filters, async) and a defense-in-depth duplicate-suppression target. Inject `AuditWriter` and `TrustedProxyResolver` via constructor (currently no constructor — add one). RestControllerAdvice beans support constructor injection.
- Add a private helper `recordDenied(Throwable ex, AuditOutcome outcome)` that ONLY writes when `AuditContext.currentAction()` is non-blank AND a guard flag indicates the aspect did not already record it. To prevent DOUBLE audit rows (aspect @AfterThrowing already records for mutating controller methods), introduce a sentinel: AuditContext gains `markRecorded()`/`isRecorded()` (boolean in Ctx) — the interceptor sets it after writing; GlobalExceptionHandler skips writing if isRecorded(). RECOMMENDED simpler alternative: do NOT write audit rows from GlobalExceptionHandler for the cases the aspect covers; instead ONLY explicitly audit authn/authz denials that have NO controller action (handleAuth/handleAccessDenied) using a dedicated action code, gated by isRecorded(). Pick the sentinel approach and document.
- In `handleAccessDenied`: if !AuditContext.isRecorded(), write `writer.record(currentActorUserId, currentActorOrgId, actionOrDefault("access.denied"), currentTargetType, currentTargetId, payloadWithError, resolveIp(), AuditOutcome.DENIED, false)`.
- In `handleAuth` (AuthenticationException): same pattern with default action `auth.denied`, outcome DENIED.
- Do NOT add a write in handleGeneric for every 500 unless desired — the aspect already covers mutating controllers; for non-mutating 500s it is optional. Keep scope tight to authz/authn to satisfy #4/#5/#6 without flooding.
- Add imports: AuditWriter, AuditOutcome, AuditContext, TrustedProxyResolver, HttpServletRequest (obtain via RequestContextHolder helper mirroring AuditInterceptor.currentRequest).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/AuthController.java`
- Inject `AuditWriter` and `TrustedProxyResolver` into the constructor (add params + fields).
- In `login(...)`: the lockout branch (`if (loginAttempt.isLocked(email))`) must explicitly audit BEFORE throwing: `writer.record(null, null, "auth.login.locked", "user", null, Map.of("email", maskEmail(email)), resolveIp(), AuditOutcome.DENIED, false);` then throw. Do NOT put raw email if policy forbids; at minimum store a hashed/masked email or the email as-is per existing convention (other code stores user ids; email is the only identifier pre-auth — store it).
- In `login(...)`: the three failure branches (user not found, not active, bad password) must each call `writer.record(userIdOrNull, null, "auth.login.failed", "user", userIdOrNull?.toString(), Map.of("email", email, "reason", <"unknown_user"|"inactive"|"bad_password">), resolveIp(), AuditOutcome.FAILED, false);` BEFORE `loginAttempt.recordFailure(email)` throws. These are written directly (not via aspect) because the method throws before reaching the @AfterReturning success path AND because login is not in the fail-closed allowlist (login must still return 401 even if audit write fails -> failClosed=false / fail-open).
- SUCCESS login path already sets AuditContext action `auth.login`; that flows through the aspect @AfterReturning as SUCCESS — keep, no change needed beyond confirming outcome defaults to SUCCESS.
- Add a private `resolveIp()` helper that grabs current HttpServletRequest via RequestContextHolder and calls proxyResolver.resolveClientIp(req); reuse the masking helper if added.
- NOTE on double-write: because these explicit failed-login writes happen inside the controller method that then THROWS, the aspect @AfterThrowing will ALSO fire for /api/v1/auth/login (it is a @PostMapping mutating endpoint within *Controller). To avoid duplicate FAILED rows, after the explicit write call `AuditContext.markRecorded()` (sentinel) so the aspect's afterThrowing skips. Implement the markRecorded/isRecorded sentinel in AuditContext and have AuditInterceptor.afterThrowing early-return when isRecorded().

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoSuccessHandler.java`
- Inject `AuditWriter` and `TrustedProxyResolver` (add constructor params + fields).
- This handler runs in the security filter chain, OUTSIDE the controller pointcut, so the aspect never sees it — it must audit explicitly (#5: all SSO/JIT provisioning).
- On missing email claim (the early `response.sendRedirect(uiBaseUrl); return;` branch): `writer.record(null, orgId, "sso.login.failed", "sso", null, Map.of("reason","missing_email_claim"), resolveIp(request), AuditOutcome.FAILED, false);`
- On JIT user creation (inside/just after `jitCreateUser`): `writer.record(user.getId(), orgId, "sso.user.provisioned", "user", user.getId().toString(), Map.of("email", email, "via","sso_jit"), resolveIp(request), AuditOutcome.SUCCESS, false);` Distinguish provisioned-new vs existing: only write provisioned when `existing.isEmpty()`.
- On membership auto-grant (`ensureMembership` actually inserts a new OrgMember): `writer.record(user.getId(), orgId, "sso.membership.provisioned", "org_member", orgId + ":" + user.getId(), Map.of("role", OrgMember.Role.MEMBER.name(), "via","sso_jit"), resolveIp(request), AuditOutcome.SUCCESS, false);` Move the 'already a member' short-circuit so we only audit on actual insert. ensureMembership currently returns void on existing — change it to return boolean (created) so the handler can decide whether to audit, OR audit inside ensureMembership.
- On successful SSO login (the final `?sso=success` redirect): `writer.record(user.getId(), orgId, "sso.login", "user", user.getId().toString(), Map.of("email", email), resolveIp(request), AuditOutcome.SUCCESS, false);`
- Add private `resolveIp(HttpServletRequest req)` delegating to proxyResolver. These writes use failClosed=false (do not block login on audit-store hiccup) but use the canonical signature so outcome is recorded.
- Note actor org context: orgId may be null when SSO session had no org; pass it through as actorOrgId.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/sso/SsoService.java`
- create() and delete() are called from SsoController @PostMapping/@DeleteMapping so the aspect covers SUCCESS via AuditContext (already set) and now FAILED via @AfterThrowing. For fail-closed correctness on these high-value config changes (they are in app.audit.fail-closed-actions), ensure the audit write happens inside the same @Transactional. Because create()/delete() are @Transactional and set AuditContext but the aspect writes AFTER method return, the recommended fail-closed pattern is to call writer.record(...AuditOutcome.SUCCESS, true) INSIDE create()/delete() before returning, and rely on AuditContext only for the fail-open aspect path. RECOMMENDED minimal change: inject AuditWriter into SsoService and, at end of create()/delete(), explicitly write the audit row with failClosed=true, then markRecorded() so the aspect skips. Mirror this for the other fail-closed services (LicenseIssuer, LicenseRevocationService, KeyService, RbacController, OrgService owner-transfer, ApiKeyService) — list them as follow-on but SSO is the in-scope one for this theme.
- Add import AuditWriter, AuditOutcome; constructor param.
- If keeping it minimal for this PR, at least confirm create/delete set AuditContext (they do) and that the new @AfterThrowing covers their failure path automatically (it does, no SsoService change strictly required for FAILED capture). The fail-closed SUCCESS write is the only reason to touch SsoService.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/JwtAuthFilter.java`
- Replace `AuditContext.setIp(request.getRemoteAddr());` with the trusted-proxy resolver so JWT requests get the real client IP only when behind a trusted proxy. Inject/obtain TrustedProxyResolver — JwtAuthFilter is constructed manually in SecurityConfig.jwtAuthFilter(), so add a TrustedProxyResolver constructor param to JwtAuthFilter and pass it from SecurityConfig (SecurityConfig must inject TrustedProxyResolver and forward it).
- Resulting line: `AuditContext.setIp(proxyResolver.resolveClientIp(request));` This satisfies #59 (trusted-proxy XFF) uniformly with the aspect.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java`
- Add `TrustedProxyResolver` as a field + constructor param (it is a @Component bean).
- Update the `jwtAuthFilter()` bean factory to pass the resolver: `return new JwtAuthFilter(sessionTokenService, authoritiesLoader, objectMapper, trustedProxyResolver);`.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/common/TrustedProxyResolver.java`
- New @Component. Bind `@Value("${app.audit.trusted-proxies:}") List<String> trustedCidrs` (or via AuditProperties). Parse each CIDR once at construction into a matcher (use Spring's `org.springframework.security.web.util.matcher.IpAddressMatcher` per CIDR — already on classpath via spring-security).
- `public String resolveClientIp(HttpServletRequest req)`: peer = req.getRemoteAddr(); if peer matches any trusted CIDR, read X-Forwarded-For, take the RIGHTMOST untrusted hop (or leftmost per policy — document: take leftmost non-trusted address). Minimal correct rule: if peer is trusted, return the FIRST (client) token of XFF trimmed; else return peer. If trustedCidrs empty, always return peer (never trust XFF).
- Handle malformed XFF defensively (return peer on parse failure). Return null only if req is null.
- Add static factory or null-safe handling so AuditInterceptor and GlobalExceptionHandler can call it with a possibly-null request.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/common/AuditProperties.java`
- New @ConfigurationProperties(prefix="app.audit") record/class with: `List<String> trustedProxies = []`, `List<String> failClosedActions = []`. Register via @EnableConfigurationProperties on ControlPanelApplication or @ConfigurationPropertiesScan. Used by TrustedProxyResolver and AuditInterceptor (and GlobalExceptionHandler if needed). Avoids scattering @Value strings.

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- Under `app:` add `audit:` block: `trusted-proxies: ${APP_AUDIT_TRUSTED_PROXIES:}` (comma list) and `fail-closed-actions:` with the default high-value list (license.issued, license.revoked, key.rotated, rbac.role.assigned, rbac.role.removed, org.owner.transferred, apikey.created, apikey.revoked, sso.provider.created, sso.provider.deleted).
- Add liquibase/runtime user separation: keep `spring.datasource.username: ${DB_USER:cp_app}` for the APP, and add `spring.liquibase.user: ${DB_OWNER_USER:cp}` / `spring.liquibase.password: ${DB_OWNER_PASS:cp}` so MIGRATIONS run as the owner while the runtime pool runs as cp_app. Document in a comment.
- NOTE: changing default DB_USER from cp to cp_app is a behavior change — gate behind env so existing local/dev (docker-compose) still works; keep default cp for now and override in docker-compose/prod. RECOMMENDED: leave spring.datasource.username default as cp_app ONLY in prod profile; in base yml keep cp to not break dev until the role migration has run. Document the ops cutover.

### [MODIFY] `control-panel-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Add include entry for the new migration AFTER 12-additional-permissions and before 99: `- include:` `    file: db/changelog/changes/13-audit-outcome-and-roles.sql`. Place it before 99 to keep numeric order; the role/grant changeset is idempotent so ordering vs 99 is not strictly required, but list 13 before 99 for clarity.

### [MODIFY] `docker-compose.yml`
- Document/optionally set the runtime app to use cp_app: add `DB_USER: cp_app`, `DB_PASS: cp_app`, plus owner creds for Liquibase `DB_OWNER_USER: cp`, `DB_OWNER_PASS: cp` under control-panel-api environment. Because Liquibase creates the cp_app role on first migration (run as cp), the app can then connect as cp_app. Add a comment that the very first boot runs migrations as cp (owner) which creates cp_app. This is the ops note realized in compose.

## Tests to add

- AuditInterceptor @AfterThrowing: POST to a mutating controller endpoint that throws AccessDeniedException writes exactly one audit_log row with outcome=DENIED and the derived action; assert no SUCCESS row written.
- AuditInterceptor @AfterThrowing: a mutating endpoint that throws a generic RuntimeException writes one row outcome=FAILED with error.class in payload; exception still propagates to GlobalExceptionHandler returning 500 (advice does not swallow).
- Login failure (unknown user / inactive / bad password): POST /api/v1/auth/login records auth.login.failed outcome=FAILED with reason and masked email, exactly ONE row (sentinel markRecorded prevents aspect duplicate), and still returns 401.
- Login lockout: after 5 failures the locked branch records auth.login.locked outcome=DENIED and returns 401; assert a locked row exists distinct from failed rows.
- Successful login records auth.login outcome=SUCCESS via the aspect (no duplicate explicit row).
- SSO JIT new user: SsoSuccessHandler with a new email writes sso.user.provisioned (SUCCESS) + sso.membership.provisioned (when orgId present and membership newly created) + sso.login; existing user writes only sso.login and no provisioned row.
- SSO missing email claim writes sso.login.failed outcome=FAILED and redirects without provisioning.
- AuditWriter fail-closed: when failClosed=true and the INSERT fails (simulate via a triggered exception / revoked grant), the exception propagates and the surrounding business transaction rolls back; when failClosed=false the same failure is swallowed and business op succeeds.
- occurred_at uses DB clock: insert a row and assert occurred_at is set by the DB (within tolerance of NOW()) even though the app bound no timestamp; verify the INSERT no longer binds OffsetDateTime.now().
- TrustedProxyResolver: with empty trusted-proxies, XFF header is ignored and getRemoteAddr() is used; with peer inside a trusted CIDR, the client token from XFF is returned; with peer outside CIDR but XFF present, XFF is ignored.
- DB least-privilege enforcement (integration with cp_app role): connecting as cp_app, INSERT into audit_log succeeds, UPDATE/DELETE on audit_log is denied by GRANT (and by trigger), and DDL on audit_log is denied; SELECT works for AuditController.
- AuditController/AuditLogDto exposes outcome: GET /api/v1/audit returns the outcome field and search still works after the schema change (ddl-auto=validate passes against the new column).
- GlobalExceptionHandler fallback: an AccessDeniedException thrown from a NON-mutating (GET) endpoint with an AuditContext action set produces a DENIED row exactly once (isRecorded sentinel prevents double when aspect already wrote).

## Risks / cross-file notes

- Package cycle: putting AuditOutcome in com.example.cp.audit while com.example.cp.common.AuditContext imports it creates a package-level cycle (audit.AuditInterceptor -> common.AuditContext -> audit.AuditOutcome). It compiles in Java but is a smell; RECOMMENDATION: define AuditOutcome in com.example.cp.common to avoid the cycle and simplify imports across AuthController/SsoSuccessHandler/GlobalExceptionHandler.
- Hibernate ddl-auto=validate: adding a NOT NULL DEFAULT 'SUCCESS' column REQUIRES the AuditLog entity to map `outcome` in the SAME change, or app startup fails schema validation. The migration and entity edit are coupled and must ship together.
- Fail-closed via aspect is unsound: @AfterReturning/@AfterThrowing run after the controller method returns but Spring's @Transactional commit boundary is the controller (or service) method exit. For fail-closed SUCCESS writes, the audit INSERT must occur INSIDE the still-open business transaction — so fail-closed SUCCESS must be written by the service method itself (e.g., SsoService.create/delete, LicenseIssuer, etc.), not by the aspect. The aspect is reliable only for fail-open SUCCESS and for DENIED/FAILED (where rollback is acceptable/expected). Document this split or fail-closed will silently not roll back.
- REQUIRES_NEW vs fail-closed: the existing AuditWriter.record uses REQUIRES_NEW which commits in a SEPARATE transaction — that is INHERENTLY fail-open and survives business rollback (good for forensics of denials, bad for atomic high-value coupling). The new failClosed path must NOT use REQUIRES_NEW; ensure the two code paths are clearly separated and that Spring self-invocation does not bypass the proxy (call the correct @Transactional method through the proxy or inline the jdbc.update in the failClosed branch under the caller's tx).
- Double audit rows: /api/v1/auth/login and SSO config endpoints can be written both explicitly AND by the aspect. The markRecorded()/isRecorded() sentinel in AuditContext must be honored by BOTH AuditInterceptor.afterThrowing/afterReturning AND GlobalExceptionHandler, or duplicates/missing rows result.
- AuditContext.clear() timing: the aspect clears in finally and JwtAuthFilter also clears in finally; explicit writes in AuthController/SsoSuccessHandler must read AuditContext (actor/ip) BEFORE clear runs. SsoSuccessHandler runs in the filter chain (no controller aspect), so it must set/use context carefully or pass values directly (recommended: pass values directly to writer.record rather than via AuditContext to avoid lifecycle coupling).
- DB role cutover ordering: the app cannot connect as cp_app until the migration (run as owner cp) has created the role and grants. If spring.datasource.username is switched to cp_app before migrations run, startup fails. Keep Liquibase on the owner connection (spring.liquibase.user=cp) and runtime pool on cp_app; verify Spring Boot uses the separate liquibase datasource creds and does not run Liquibase on the cp_app pool (it does not when spring.liquibase.user/password are set).
- Liquibase role creation idempotency & permissions: CREATE ROLE / GRANT must run as a role with CREATEROLE/owner privileges (cp). In managed Postgres (RDS) the owner may not be a superuser; CREATE ROLE may need the rds_superuser or an externally-provisioned role. Provide the ops note that role creation may be done out-of-band if the migration role lacks CREATEROLE.
- TrustedProxyResolver XFF direction policy: choosing leftmost vs rightmost hop changes spoofability. With a single trusted proxy, leftmost client token is correct; with chained proxies, you must strip exactly the count of trusted hops. The simple leftmost-when-peer-trusted rule is acceptable for one proxy but must be documented as such to avoid a false sense of anti-spoofing.
- ApiKeyAuthFilter does not set AuditContext IP/actor at all; API-key-authenticated mutating calls will derive IP only at the aspect via TrustedProxyResolver (fine) but actorUserId is null (api keys have no userId). Confirm AuditLog.actor_user_id nullable (it is) so api-key actions still audit with org context only — no change required but note the gap that API-key actor identity is only the org, not the key id, in audit rows (consider adding key id to payload as a follow-up).
- masking email in login.failed/locked rows: storing raw email of failed logins may be a minor PII consideration; align with existing convention (other rows store ids). Decide masking policy explicitly to avoid leaking enumeration data into a SELECT-readable audit table.
