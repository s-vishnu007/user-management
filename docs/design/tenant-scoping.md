# Design spec: tenant-scoping

## Shared contracts (other files depend on these — keep signatures exact)

### `tenantAccess (com.example.cp.security.TenantAccessChecker)` (bean)
- **File:** `control-panel-api/src/main/java/com/example/cp/security/TenantAccessChecker.java`
- **Purpose:** Central per-target-org access checker used by all resource-scoped @PreAuthorize SpEL. Resolves the TARGET resource's org and checks OrgMember membership/role. super_admin is the ONLY global bypass. API-key principals are constrained to their bound orgId. Replaces the global-authority short-circuits in SubscriptionAccess and the global hasAuthority(...) branches scattered across controllers.
- **Signature/contract:**

```
@Component("tenantAccess") class TenantAccessChecker. Methods (all return boolean, all UUID/String args nullable-safe, all default-deny):
 - boolean canAccessOrg(UUID orgId)  // membership in target org (any role) OR super_admin OR api-key bound to orgId
 - boolean canManageOrg(UUID orgId)  // role >= ADMIN in target org OR super_admin (api-key NOT allowed unless future write scope; default deny for keys)
 - boolean canReadSubscription(UUID subscriptionId)  // resolve sub.orgId then canAccessOrg
 - boolean canWriteSubscription(UUID subscriptionId)  // resolve sub.orgId then canManageOrg (members below ADMIN denied write/suspend/cancel/overrides)
 - boolean canReadSubscriptionInOrg(UUID orgId)  // alias of canAccessOrg for the list-by-org path
 - boolean canWriteSubscriptionInOrg(UUID orgId)  // canManageOrg for the create path
 - boolean canReadLicenseByJti(String jti)  // resolve jti->subId->orgId then canAccessOrg
 - boolean canIssueLicenseForSubscription(UUID subscriptionId)  // canWriteSubscription (issue is a write op)
 - boolean canReadUsageForSubscription(UUID subscriptionId)  // canReadSubscription
 - boolean canIngestUsageForJti(String jti)  // resolve jti->subId->orgId then canAccessOrg (api-key must be bound to that org)
Internal helpers: Optional<UUID> resolveOrgForSubscription(UUID), Optional<UUID> resolveOrgForJti(String), boolean isMemberOf(AuthenticatedUser,UUID), boolean isManagerOf(AuthenticatedUser,UUID).
```

### `AuthenticatedUser (modified)` (record)
- **File:** `control-panel-api/src/main/java/com/example/cp/common/AuthenticatedUser.java`
- **Purpose:** Carry the API-key binding into SpEL/SecurityUtils so the checker can constrain API-key principals to their bound org and recognize that key scopes must NOT satisfy global cross-org branches.
- **Signature/contract:**

```
record AuthenticatedUser(UUID userId, String email, boolean superAdmin, Set<String> authorities, Collection<? extends GrantedAuthority> grantedAuthorities, boolean apiKey, UUID apiKeyOrgId). Add helpers: boolean isApiKey() (returns apiKey); boolean isApiKeyBoundTo(UUID orgId) (apiKey && apiKeyOrgId!=null && apiKeyOrgId.equals(orgId)). Keep existing hasAuthority(String). Provide a static factory/overload or update all 2 call sites (JwtAuthFilter, ApiKeyAuthFilter) to the new canonical constructor.
```

### `app.api-keys.creatable-scopes (allow-list)` (config-property)
- **File:** `control-panel-api/src/main/resources/application.yml`
- **Purpose:** Org-scoped allow-list constraining which scopes an API key may be created with. Prevents minting keys with global/cross-org authorities (e.g. subscription.read, license.issue, subscription.write).
- **Signature/contract:**

```
app.api-keys.creatable-scopes: [usage.ingest, usage.read, license.read]  // bound via @ConfigurationProperties record ApiKeyScopePolicy or read in ApiKeyService; default list above. Any requested scope not in this set => 400.
```

### `13-usage-permissions.sql (changeset cp:13-usage-and-key-scope-permissions)` (db-migration)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-usage-permissions.sql`
- **Purpose:** Add the usage.read / usage.ingest permission codes referenced by SpEL and the org-scoped API-key allow-list, and grant them to SUPER_ADMIN (and usage.read to ORG roles). usage.read/usage.ingest are NOT currently in any migration despite being used in @PreAuthorize.
- **Signature/contract:**

```
INSERT INTO permissions (code,name,description,category) VALUES ('usage.read',...,'usage'),('usage.ingest',...,'usage'),('license.read'... if not present),('subscription.suspend'/'subscription.cancel' already in 12) ON CONFLICT DO NOTHING; then grant all-permissions->SUPER_ADMIN and usage.read->ORG_OWNER/ORG_ADMIN/ORG_MEMBER/VIEWER. Must be added as a new <include> in db.changelog-master.yaml AFTER 12-additional-permissions.sql and before 99-auth-password-reset.sql.
```

## File edits

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/common/AuthenticatedUser.java`
- depends on: AuthenticatedUser (modified)
- Add two fields to the record: `boolean apiKey` and `UUID apiKeyOrgId`. New canonical signature: `record AuthenticatedUser(UUID userId, String email, boolean superAdmin, Set<String> authorities, Collection<? extends GrantedAuthority> grantedAuthorities, boolean apiKey, UUID apiKeyOrgId)`.
- Keep `hasAuthority(String code)` exactly as-is (still `superAdmin || authorities.contains(code)`).
- Add `public boolean isApiKey() { return apiKey; }` and `public boolean isApiKeyBoundTo(UUID orgId) { return apiKey && apiKeyOrgId != null && apiKeyOrgId.equals(orgId); }`.
- Optionally add a static convenience factory `static AuthenticatedUser forUser(UUID userId, String email, boolean superAdmin, Set<String> authorities, Collection<? extends GrantedAuthority> granted)` that passes `apiKey=false, apiKeyOrgId=null`, to minimize churn at the JWT call site.
- COMPILE RISK: every `new AuthenticatedUser(...)` must be updated (JwtAuthFilter line 72-73, ApiKeyAuthFilter line 50-51). Grep for `new AuthenticatedUser(` before finishing.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyAuthFilter.java`
- depends on: AuthenticatedUser (modified)
- When constructing the principal (currently line 50-51), set the new flags: `new AuthenticatedUser(null, "apikey:" + key.getId(), false, scopes, authorities, true, key.getOrgId())`. This makes the bound orgId reachable via SecurityUtils.currentUser() inside the checker.
- Keep the existing `ApiKeyAuthentication` token (it still carries orgId for any callers that read it), but the authoritative binding for the checker is now on the principal.
- Note: api-key scopes are still mapped to GrantedAuthority so endpoint-level `hasAuthority('usage.read')` etc. continues to function; cross-org safety now comes from the checker requiring `isApiKeyBoundTo(targetOrg)`, NOT from removing the authorities.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/JwtAuthFilter.java`
- depends on: AuthenticatedUser (modified)
- Update the `new AuthenticatedUser(...)` at lines 72-73 to pass `apiKey=false, apiKeyOrgId=null` (or call the new `AuthenticatedUser.forUser(...)` factory). Behavior for human users is unchanged.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/security/TenantAccessChecker.java`
- depends on: tenantAccess (com.example.cp.security.TenantAccessChecker), AuthenticatedUser (modified)
- NEW central checker bean `@Component("tenantAccess")`. Constructor-inject `SubscriptionRepository subRepo`, `OrgMemberRepository memberRepo`, `LicenseTokenRepository tokenRepo` (resolve jti->subId without a circular dep on LicenseLookup). Optionally reuse `OrgService`/`OrgAccessChecker` for role rank, but to avoid coupling, replicate the small rank() logic or call `OrgAccessChecker` if no cycle.
- Implement `boolean canAccessOrg(UUID orgId)`: get `SecurityUtils.currentUser()`; if empty -> false; if `u.superAdmin()` -> true; if `u.isApiKey()` -> return `u.isApiKeyBoundTo(orgId)` (api-key membership = its bound org only, NO global-scope bypass); else (human) -> `u.userId()!=null && memberRepo.findByOrgIdAndUserId(orgId, u.userId()).isPresent()`.
- Implement `boolean canManageOrg(UUID orgId)`: empty->false; superAdmin->true; api-key -> default DENY (return false) unless you later add a write scope; human -> resolve role via memberRepo and require rank>=ADMIN. Provide private `int rank(OrgMember.Role)` mirroring OrgAccessChecker (OWNER 4, ADMIN 3, MEMBER 2, VIEWER 1).
- Implement `Optional<UUID> resolveOrgForSubscription(UUID subId)`: `subId==null?empty: subRepo.findById(subId).map(Subscription::getOrgId)`.
- Implement `Optional<UUID> resolveOrgForJti(String jti)`: `tokenRepo.findByJti(jti).map(LicenseToken::getSubscriptionId).flatMap(this::resolveOrgForSubscription)`.
- canReadSubscription(UUID id) = resolveOrgForSubscription(id).map(this::canAccessOrg).orElse(false).
- canWriteSubscription(UUID id) = resolveOrgForSubscription(id).map(this::canManageOrg).orElse(false).
- canReadSubscriptionInOrg(UUID orgId)=canAccessOrg(orgId); canWriteSubscriptionInOrg(UUID orgId)=canManageOrg(orgId).
- canReadLicenseByJti(String jti)=resolveOrgForJti(jti).map(this::canAccessOrg).orElse(false).
- canIssueLicenseForSubscription(UUID subId)=canWriteSubscription(subId).
- canReadUsageForSubscription(UUID subId)=canReadSubscription(subId).
- canIngestUsageForJti(String jti)=resolveOrgForJti(jti).map(this::canAccessOrg).orElse(false). For api-key ingest this enforces the key is bound to the same org that owns the license.
- Default-deny everywhere: null arg, missing resource, or unresolved org => false. Do NOT consult any global authority (subscription.read/license.issue) inside this bean — endpoint-level authority checks stay in @PreAuthorize and are AND/OR-composed there, but cross-org bypass is impossible because the checker ignores authorities for resolution.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionAccess.java`
- depends on: tenantAccess (com.example.cp.security.TenantAccessChecker)
- REMOVE the global-authority short-circuits. In `canReadSubscription` delete the `if (u.superAdmin() || u.hasAuthority("subscription.read")) return true;` line — keep only superAdmin via the checker. In `canDownloadLicense` delete the `hasAuthority('license.issue') || hasAuthority('subscription.read')` short-circuit.
- RECOMMENDED: delete this class entirely and migrate all references to `@tenantAccess`. SubscriptionAccess currently provides `isOrgMember`, `canReadSubscription`, `canDownloadLicense` — all are superseded by TenantAccessChecker (`canAccessOrg`, `canReadSubscription`, `canReadLicenseByJti`). If deleted, ensure no remaining @PreAuthorize references `@subAccess` (see SubscriptionController, LicenseController edits).
- If kept for transition: make every method delegate to `tenantAccess` and remove the authority bypasses, so there is a single source of truth.
- COMPILE RISK: deleting the bean breaks any SpEL referencing `@subAccess`; all such references are in SubscriptionController and LicenseController (this spec rewrites both).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/subscriptions/SubscriptionController.java`
- depends on: tenantAccess (com.example.cp.security.TenantAccessChecker)
- listByOrg (line 29): replace `@PreAuthorize("@subAccess.isOrgMember(#orgId) or hasAuthority('subscription.read')")` with `@PreAuthorize("@tenantAccess.canReadSubscriptionInOrg(#orgId)")`. The global `subscription.read` OR-branch is removed (this is the cross-org leak #11/#13).
- create (line 35): replace `@PreAuthorize("hasAuthority('subscription.write')")` with `@PreAuthorize("@tenantAccess.canWriteSubscriptionInOrg(#orgId)")`. Previously a global `subscription.write` let any holder create subs in ANY org.
- get (line 48): replace `@PreAuthorize("@subAccess.canReadSubscription(#id)")` with `@PreAuthorize("@tenantAccess.canReadSubscription(#id)")`.
- suspend (line 54): replace `hasAuthority('subscription.write')` with `@PreAuthorize("@tenantAccess.canWriteSubscription(#id)")`.
- reactivate (line 60): replace with `@PreAuthorize("@tenantAccess.canWriteSubscription(#id)")`.
- cancel (line 66): replace with `@PreAuthorize("@tenantAccess.canWriteSubscription(#id)")`.
- addOverride (line 72): replace with `@PreAuthorize("@tenantAccess.canWriteSubscription(#id)")`.
- removeOverride (line 81): replace with `@PreAuthorize("@tenantAccess.canWriteSubscription(#id)")`.
- All path-variable names (#orgId, #id) already match method params — no signature changes needed.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/licenses/LicenseController.java`
- depends on: tenantAccess (com.example.cp.security.TenantAccessChecker)
- issue (line 47): replace `@PreAuthorize("hasAuthority('license.issue')")` with `@PreAuthorize("@tenantAccess.canIssueLicenseForSubscription(#subId)")`. Path var is `subId`. Global `license.issue` previously allowed issuing licenses for ANY org's subscription (#12/#40).
- download (line 69): replace `@PreAuthorize("hasAuthority('license.issue') or hasAuthority('subscription.read') or @subAccess.canDownloadLicense(@licenseLookup.subscriptionId(#jti))")` with `@PreAuthorize("@tenantAccess.canReadLicenseByJti(#jti)")`. Removes both global short-circuits and the LicenseLookup indirection. (LicenseLookup bean may now be unused — see risks.)
- list (line 107): replace `@PreAuthorize("hasAuthority('subscription.read') or hasAuthority('license.issue')")` with `@PreAuthorize("@tenantAccess.canReadSubscription(#subscriptionId)")` AND make `subscriptionId` REQUIRED. Change `@RequestParam(required=false) UUID subscriptionId` to `@RequestParam UUID subscriptionId`. Rationale: an unscoped license list across all orgs is a cross-org leak (#41); force a subscription scope and authorize against its org. Drop the all-rows/findAll branch (lines 118-120) so callers cannot enumerate every license; if a global list is still required for super_admins, gate that separate endpoint on `hasAuthority('SUPER_ADMIN')`.
- getOne (line 125): replace `@PreAuthorize("hasAuthority('subscription.read') or hasAuthority('license.issue')")` with `@PreAuthorize("@tenantAccess.canReadLicenseByJti(#jti)")`.
- revoke (line 98): leave as `hasAuthority('license.revoke')` for now OR (preferred for full tenant-scoping) change to `@PreAuthorize("@tenantAccess.canWriteSubscription(@licenseLookup.subscriptionId(#jti)) or hasAuthority('SUPER_ADMIN')")`. If left unchanged, note in risks that revoke remains a global-authority path. Recommend scoping it via a new `tenantAccess.canRevokeLicenseByJti(String jti)` = resolveOrgForJti -> canManageOrg.
- Add `canRevokeLicenseByJti(String jti)` to TenantAccessChecker if revoke is scoped (resolveOrgForJti(jti).map(this::canManageOrg).orElse(false)).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestController.java`
- depends on: tenantAccess (com.example.cp.security.TenantAccessChecker)
- ingest (line 36-37): the body's `jti` resolves the target subscription/org. Add a method-level guard. Two options: (a) add `@PreAuthorize` that reads the body — not directly possible since jti is in the request body, so prefer (b): keep `SecurityUtils.requireUser()` then, BEFORE calling service.ingest, call the checker explicitly: `if (!tenantAccess.canIngestUsageForJti(body.jti())) throw ApiException.forbidden(...)`. Inject `TenantAccessChecker tenantAccess` into the controller constructor. This binds api-key ingest to the key's org (#42/#43).
- Alternatively expose a SpEL form `@PreAuthorize("@tenantAccess.canIngestUsageForJti(#body.jti)")` — verify SpEL can read the record accessor `#body.jti()`; the explicit in-method check is lower-risk and recommended.
- Add `ApiException.forbidden(String)` use (confirm it exists in ApiException; if only unauthorized/notFound/badRequest exist, add a forbidden factory returning 403 — see risks).
- listUsage (line 55): replace `@PreAuthorize("hasAuthority('subscription.read') or hasAuthority('usage.read')")` with `@PreAuthorize("@tenantAccess.canReadUsageForSubscription(#subId)")`. Removes the global usage.read/subscription.read cross-org read (#15/#16). Path var `subId` already present.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyService.java`
- depends on: app.api-keys.creatable-scopes (allow-list)
- Constrain creatable scopes: in `create(UUID orgId, String name, Set<String> scopes)`, after null-normalizing, validate every requested scope against the org-scoped allow-list (e.g. injected `ApiKeyScopePolicy` or `@Value("${app.api-keys.creatable-scopes}")` set). Any scope not in the allow-list => `throw ApiException.badRequest("Scope not permitted for API keys: " + s)`. Allow-list default: {usage.ingest, usage.read, license.read}. Explicitly EXCLUDE subscription.read, subscription.write, license.issue, license.revoke, apikey.write and any *.write so keys cannot satisfy global cross-org branches (#3/#14).
- Scope revoke by (id, orgId): change `revoke(UUID id)` to `revoke(UUID orgId, UUID id)`. Body: `ApiKey k = repo.findById(id).orElseThrow(notFound); if (!k.getOrgId().equals(orgId)) throw ApiException.notFound("API key not found");` (return 404, not 403, to avoid cross-org existence disclosure) then revoke as before. This closes the IDOR where any apikey.write holder revoked keys in other orgs (#14).
- Optional hardening: in `parseScopes`/`verify` flow, also intersect stored scopes with the allow-list at auth time so legacy keys with over-broad scopes are neutralized. At minimum, document this as follow-up.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyController.java`
- depends on: tenantAccess (com.example.cp.security.TenantAccessChecker), app.api-keys.creatable-scopes (allow-list)
- create (line 32): replace `@PreAuthorize("hasAuthority('apikey.write') or @orgAccess.isOwnerOrAdmin(#orgId)")` with `@PreAuthorize("@tenantAccess.canManageOrg(#orgId)")` (or keep `@orgAccess.isOwnerOrAdmin(#orgId)` since that already scopes to target org; the issue is the global `hasAuthority('apikey.write')` OR-branch — REMOVE that branch). Net: drop `hasAuthority('apikey.write') or`.
- list (line 41): replace `@PreAuthorize("hasAuthority('apikey.read') or @orgAccess.isMember(#orgId)")` with `@PreAuthorize("@orgAccess.isMember(#orgId)")` (remove global `apikey.read` branch) or `@tenantAccess.canAccessOrg(#orgId)`.
- revoke (line 47-49): replace annotation `hasAuthority('apikey.write') or @orgAccess.isOwnerOrAdmin(#orgId)` with `@PreAuthorize("@orgAccess.isOwnerOrAdmin(#orgId)")` (or `@tenantAccess.canManageOrg(#orgId)`), AND change the call to the new scoped service signature: `service.revoke(orgId, id);`.
- Ensure controller still receives both `@PathVariable UUID orgId` and `@PathVariable UUID id` (already present).

### [NEW FILE] `control-panel-api/src/main/resources/db/changelog/changes/13-usage-permissions.sql`
- depends on: 13-usage-permissions.sql (changeset cp:13-usage-and-key-scope-permissions)
- NEW Liquibase changeset `cp:13-usage-and-key-scope-permissions`. INSERT permissions for `usage.read` (category 'usage') and `usage.ingest` (category 'usage') — these codes are referenced by @PreAuthorize / api-key scopes but are absent from migrations 02 and 12. Use ON CONFLICT (code) DO NOTHING.
- Grant ALL permissions to SUPER_ADMIN (the CROSS JOIN pattern from 12) so super-admin retains full access including the new usage codes.
- Grant `usage.read` to ORG_OWNER, ORG_ADMIN, ORG_MEMBER, VIEWER and `usage.ingest` to ORG_OWNER/ORG_ADMIN (matching role grant style in 02-rbac.sql).
- Add `--rollback` deleting the new role_permissions and permissions rows.
- COMPILE/RUNTIME RISK: Liquibase checksums are content-addressed; adding a NEW file is safe, editing an applied changeset is not. Append a new <include> entry to db.changelog-master.yaml.

### [MODIFY] `control-panel-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- depends on: 13-usage-permissions.sql (changeset cp:13-usage-and-key-scope-permissions)
- Insert a new include for `db/changelog/changes/13-usage-permissions.sql` AFTER the 12-additional-permissions.sql include (line 26-27) and BEFORE the 99-auth-password-reset.sql include (line 28-29). Ordering matters: 13 references permissions/roles created in 02 and 12.

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- depends on: app.api-keys.creatable-scopes (allow-list)
- Add config block `app.api-keys.creatable-scopes: [usage.ingest, usage.read, license.read]`. Confirm the file is application.yml vs application.properties (Glob the resources dir); if properties, use `app.api-keys.creatable-scopes=usage.ingest,usage.read,license.read`. Bind via a small `@ConfigurationProperties(prefix="app.api-keys")` record or `@Value` in ApiKeyService.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/common/ApiException.java`
- VERIFY a `forbidden(String)` factory returning HTTP 403 exists; UsageIngestController's explicit ingest guard needs it. If absent, add `public static ApiException forbidden(String detail)` mirroring the existing `unauthorized`/`notFound`/`badRequest` factories (read this file before editing). If you prefer not to touch ApiException, throw `org.springframework.security.access.AccessDeniedException` from the controller guard instead (Spring maps it to 403).

## Tests to add

- TenantAccessChecker.canAccessOrg: super_admin -> true for any org; non-member human -> false; member human -> true; api-key bound to orgA -> true for orgA, false for orgB; unauthenticated -> false.
- Cross-org subscription read (finding #11/#13): user who is member of orgA but holds a GLOBAL subscription.read authority CANNOT GET /subscriptions/{id} where the sub belongs to orgB (expect 403). Previously allowed via hasAuthority short-circuit.
- Subscription write scoping (#13): VIEWER/MEMBER of the owning org CANNOT suspend/cancel/reactivate/addOverride (canWriteSubscription requires ADMIN+); ADMIN/OWNER CAN; super_admin CAN.
- Subscription create scoping: holder of global subscription.write but NOT a member of target orgId -> 403 on POST /orgs/{orgId}/subscriptions.
- License issue scoping (#12/#40): holder of global license.issue who is not an ADMIN/member of the sub's org -> 403 on POST /subscriptions/{subId}/licenses; owner/admin of that org -> 201.
- License download scoping: member of the license's org -> 200; member of a different org with global subscription.read -> 403; super_admin -> 200; unknown jti -> 403/404 (default deny).
- License list scoping (#41): GET /licenses with no subscriptionId -> 400 (required); with subscriptionId in another org -> 403; in own org -> 200.
- Usage ingest org-binding (#42/#43): api-key bound to orgA ingesting against a jti whose license belongs to orgA -> 202; same key against a jti belonging to orgB -> 403; human member of the license's org -> 202; non-member -> 403.
- Usage read scoping (#15/#16): GET /subscriptions/{subId}/usage by member of the sub's org -> 200; by holder of global usage.read who is not a member -> 403.
- API-key creatable-scope allow-list (#3/#14): POST /orgs/{orgId}/api-keys with scopes={subscription.read} or {license.issue} or {subscription.write} -> 400; with {usage.ingest,usage.read,license.read} -> 201.
- API-key revoke org scoping IDOR (#14): orgA owner revoking a key id that belongs to orgB -> 404 (not 403/204); orgA owner revoking own org's key -> 204.
- API-key principal cannot satisfy global cross-org branch: a key with scope usage.read used against another org's subscription usage -> 403 (checker ignores authorities for cross-org resolution).
- Regression: super_admin retains full access to every migrated endpoint (sanity that bypass path is intact).
- ApiKeyController.create authority branch removed: a user with global apikey.write but no ADMIN role in target org -> 403 (previously 200).

## Risks / cross-file notes

- AuthenticatedUser is a record with a positional constructor; adding fields breaks BOTH existing `new AuthenticatedUser(...)` call sites (JwtAuthFilter ~line 72-73, ApiKeyAuthFilter ~line 50-51). Update both in the same change or compilation fails. Grep `new AuthenticatedUser(` to be exhaustive.
- SecurityUtils.currentUser() returns the principal for BOTH JWT and API-key auth (ApiKeyAuthentication.getPrincipal() returns an AuthenticatedUser). For api-key principals userId is null, so memberRepo.findByOrgIdAndUserId(orgId, null) must never be reached — the checker must branch on isApiKey() BEFORE the membership query, else a null userId query may match nothing (ok) or NPE depending on repo. Order the checks: superAdmin -> isApiKey -> human membership.
- Removing the global `subscription.read` / `license.issue` / `usage.read` OR-branches changes behavior for any human operator who relied on a GLOBAL role assignment (user_roles.org_id IS NULL) instead of org membership. Per PermissionService, global roles grant authorities but the new checker resolves access via OrgMember membership ONLY (plus super_admin). Operators who were global-but-not-super-admin will lose cross-org access. Confirm this is intended (it is the point of the theme); if platform-ops need broad read, they should be SUPER_ADMIN or org members. Call this out for the reviewer.
- LicenseController.list currently supports findAll() across all orgs; making subscriptionId required and dropping findAll removes the admin enumeration path. If a super-admin global list is needed, add a separate endpoint gated on hasAuthority('SUPER_ADMIN'). Frontend callers of /api/v1/licenses with no params will break (now 400) — coordinate.
- UsageIngestController.ingest authorizes on a body field (jti), not a path variable, so @PreAuthorize SpEL on #body.jti is fragile (record accessor access in SpEL). Prefer the explicit in-method check via injected TenantAccessChecker; this also means the controller constructor signature changes (add TenantAccessChecker) — verify no other wiring depends on the old single-arg constructor.
- LicenseLookup bean (@licenseLookup) becomes unused once download/list SpEL stops referencing it (unless revoke scoping reuses it). Leaving it is harmless; if removed, ensure no remaining SpEL references it.
- If SubscriptionAccess (@subAccess) is deleted, every @PreAuthorize referencing @subAccess must already be migrated (SubscriptionController.get/listByOrg, LicenseController.download). A missed reference yields a runtime SpEL bean-not-found (500/403) only when that endpoint is hit, NOT a compile error — exhaustively grep `@subAccess` after edits.
- TenantAccessChecker injecting LicenseTokenRepository + SubscriptionRepository while LicenseIssuer/SubscriptionService already depend on these is fine (repos are singletons, no cycle). Avoid injecting LicenseController/SubscriptionController-layer beans to prevent cycles.
- ApiKeyService.revoke signature change (UUID)->(UUID,UUID) breaks any other caller; grep `service.revoke(` / `.revoke(` in apikeys package — currently only ApiKeyController. Tests calling revoke must update.
- Liquibase: do NOT edit already-applied changesets (02, 12). Only ADD 13-*.sql and a new include. Editing applied SQL changes its checksum and fails validation on existing databases.
- The api-key scope allow-list must be enforced at CREATE time AND ideally re-validated at AUTH time, otherwise pre-existing keys (seeded/manually inserted) with broad scopes still mint cross-org authorities. The new TenantAccessChecker neutralizes the cross-org effect for resource-scoped endpoints regardless, but endpoint-level hasAuthority(...) on non-tenant-scoped endpoints (if any) would still honor those scopes. Recommend the auth-time intersection as a fast-follow.
