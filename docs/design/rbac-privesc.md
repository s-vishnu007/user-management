# Design spec: rbac-privesc

## Shared contracts (other files depend on these — keep signatures exact)

### `rbacAuthz` (spel-bean)
- **File:** `control-panel-api/src/main/java/com/example/cp/rbac/RbacAuthorizationService.java`
- **Purpose:** Centralized RBAC privilege-escalation guard reused by RbacController.assignRole and (rank logic mirrored in) OrgService.addMember. New @Service bean. Re-queries org-scoped permissions via PermissionService since SecurityContext authorities are global-only (JwtAuthFilter computes authoritiesFor(userId,null)).
- **Signature/contract:**

```
@Service public class RbacAuthorizationService { public RbacAuthorizationService(PermissionService permissionService, RolePermissionRepository rolePermissionRepository, PermissionRepository permissionRepository); /** Validates an actor may assign `role` (scoped to orgId, null=global) to a target. Throws ApiException.forbidden/badRequest on violation. */ public void assertCanAssignRole(AuthenticatedUser actor, Role role, UUID targetUserId, UUID orgId); }
```

### `role.assign` (config-property)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-rbac-assign-permissions.sql`
- **Purpose:** New permission authority code that gates RbacController.assignRole/removeRole instead of user.write. Seeded into permissions table and granted to SUPER_ADMIN (and ORG_OWNER for org-scoped assignment). Used in @PreAuthorize("hasAuthority('role.assign')").
- **Signature/contract:**

```
permissions.code = 'role.assign', category='rbac'
```

### `rbac.read` (config-property)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-rbac-assign-permissions.sql`
- **Purpose:** New read authority gating GET /api/v1/rbac/roles and /permissions catalog endpoints via @PreAuthorize("hasAuthority('rbac.read')"). Seeded and granted to SUPER_ADMIN, ORG_OWNER, ORG_ADMIN.
- **Signature/contract:**

```
permissions.code = 'rbac.read', category='rbac'
```

### `13-rbac-assign-permissions.sql` (db-migration)
- **File:** `control-panel-api/src/main/resources/db/changelog/changes/13-rbac-assign-permissions.sql`
- **Purpose:** Liquibase formatted SQL seeding role.assign + rbac.read permissions and their role_permissions grants; must be added to db.changelog-master.yaml include list before 99-auth-password-reset.sql.
- **Signature/contract:**

```
--changeset cp:13-rbac-permissions ; INSERT ... ON CONFLICT (code) DO NOTHING; INSERT INTO role_permissions ... ON CONFLICT DO NOTHING
```

## File edits

### [NEW FILE] `control-panel-api/src/main/resources/db/changelog/changes/13-rbac-assign-permissions.sql`
- Create NEW Liquibase formatted SQL file. First line exactly: --liquibase formatted sql
- Add changeset header: --changeset cp:13-rbac-permissions-seed
- INSERT the two new permission rows (idempotent, matching 12-additional-permissions.sql style): INSERT INTO permissions (code, name, description, category) VALUES ('role.assign','Assign Roles','Assign and remove RBAC roles to users','rbac'), ('rbac.read','Read RBAC Catalog','View roles and permissions catalog','rbac') ON CONFLICT (code) DO NOTHING;
- Grant role.assign + rbac.read to SUPER_ADMIN explicitly (SUPER_ADMIN already gets all via 02/12 cross-join, but make it self-contained and idempotent): INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('role.assign','rbac.read') WHERE r.code='SUPER_ADMIN' ON CONFLICT DO NOTHING;
- Grant role.assign to ORG_OWNER so org owners can assign org-scoped roles: INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code='role.assign' WHERE r.code='ORG_OWNER' ON CONFLICT DO NOTHING;
- Grant rbac.read to ORG_OWNER and ORG_ADMIN: INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code='rbac.read' WHERE r.code IN ('ORG_OWNER','ORG_ADMIN') ON CONFLICT DO NOTHING;
- Add --rollback line removing the role_permissions for these two codes then DELETE FROM permissions WHERE code IN ('role.assign','rbac.read'); mirror the rollback style used at the bottom of 12-additional-permissions.sql.
- NOTE: do NOT remove user.write from the existing assign endpoints in SQL; user.write keeps its own meaning (modify users). role.assign is the new dedicated authority enforced in code.

### [MODIFY] `control-panel-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- depends on: 13-rbac-assign-permissions.sql
- Insert a new include block for changes/13-rbac-assign-permissions.sql immediately AFTER the 12-additional-permissions.sql include (line 26-27) and BEFORE the 99-auth-password-reset.sql include (line 28-29).
- Exact YAML block to add: '  - include:\n      file: db/changelog/changes/13-rbac-assign-permissions.sql'
- Keep ordering numeric/sequential so Liquibase applies 13 after 12; do not renumber 99.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/rbac/RbacAuthorizationService.java`
- depends on: rbacAuthz
- Create NEW @Service class com.example.cp.rbac.RbacAuthorizationService.
- Constructor-inject PermissionService permissionService, RolePermissionRepository rolePermissionRepository, PermissionRepository permissionRepository.
- Define a private static final ordered map of RBAC role-code -> rank used to detect outranking, matching the org hierarchy: SUPER_ADMIN=100, ORG_OWNER=4, ORG_ADMIN=3, ORG_MEMBER=2, VIEWER=1 (mirrors OrgAccessChecker.rank). Provide private int rankOf(String roleCode) returning the value or 0 for unknown codes.
- Add public void assertCanAssignRole(AuthenticatedUser actor, Role role, UUID targetUserId, UUID orgId). Logic in order: (1) if actor==null throw ApiException.unauthorized('Not authenticated'). (2) Block system/super roles: if (\"SUPER_ADMIN\".equals(role.getCode())) throw ApiException.forbidden(\"SUPER_ADMIN cannot be assigned via the API\"); if (role.isSystem()) throw ApiException.forbidden(\"System roles cannot be assigned via the API\"). IMPORTANT: confirm with team — all 5 seeded roles are is_system=TRUE in 02-rbac.sql, so this blanket block would forbid assigning ANY currently-seeded role except future non-system roles; the intended design (#1/#9/#64) is to forbid SUPER_ADMIN and is_system roles, so for the org/global model the assignable roles are expected to be non-system roles. See risks. If product wants ORG_* assignable, either flip those seeds to is_system=FALSE in migration 13 OR special-case allow ORG_OWNER/ORG_ADMIN/ORG_MEMBER/VIEWER while still blocking SUPER_ADMIN — pick one and encode it; spec assumes 'forbid SUPER_ADMIN + is_system' literally and notes the seed implication.
- (3) Scope authorization: if orgId==null require platform authority — if (!actor.superAdmin() && !actor.hasAuthority(\"role.assign\")) throw ApiException.forbidden(\"Global role assignment requires platform authority\"). The @PreAuthorize already enforces role.assign, but re-assert here because superAdmin bypasses and org-scoped path differs.
- (4) Privilege amplification: compute Set<String> actorPerms = actor.superAdmin() ? null : permissionService.permissionsFor(actor.userId(), orgId); (orgId may be null -> global perms). Compute the permission codes the target ROLE would grant: rolePermissionRepository.findByRoleId(role.getId()) -> map permissionId -> permissionRepository.findById(...).code (or add a JPQL projection, see risks). If actor is not superAdmin, for each code granted by the role, if !actorPerms.contains(code) throw ApiException.forbidden(\"Cannot grant authority you do not hold: \" + code).
- (5) Rank/self-elevation guard for the GLOBAL/platform case is covered by amplification (a non-super actor can never hold the SUPER_ADMIN superset). For self-assignment specifically: if (targetUserId != null && targetUserId.equals(actor.userId()) && !actor.superAdmin()) throw ApiException.forbidden(\"Cannot assign roles to yourself\") — prevents self-elevation regardless of amplification edge cases.
- Keep all checks throwing ApiException (mapped to 403/400/401 by existing handler). Do not catch.
- Optionally expose helper Set<String> permissionCodesForRole(UUID roleId) for unit testing.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/rbac/RbacController.java`
- depends on: rbacAuthz, role.assign, rbac.read
- Add field: private final RbacAuthorizationService rbacAuthz; and PermissionService is NOT needed here (service handles it). Inject RbacAuthorizationService in the constructor (extend the existing constructor param list at lines 30-36 and assign). Update the constructor signature accordingly.
- Add import: com.example.cp.common.AuthenticatedUser and com.example.cp.common.SecurityUtils.
- listRoles() (line 38-42): add @PreAuthorize(\"hasAuthority('rbac.read')\") above @GetMapping(\"/roles\").
- listPermissions() (line 44-48): add @PreAuthorize(\"hasAuthority('rbac.read')\") above @GetMapping(\"/permissions\").
- assignRole() (line 50-70): CHANGE @PreAuthorize from \"hasAuthority('user.write')\" to \"hasAuthority('role.assign')\".
- Inside assignRole, AFTER resolving Role role (line 55-56) and BEFORE the duplicate-assignment check (line 57): obtain actor via AuthenticatedUser actor = SecurityUtils.requireUser(); then call rbacAuthz.assertCanAssignRole(actor, role, userId, body.orgId()); This runs the system-role block, scope authz, amplification, and self-elevation guard before any write.
- removeRole() (line 72-85): CHANGE @PreAuthorize from \"hasAuthority('user.write')\" to \"hasAuthority('role.assign')\" for consistency (removing a role is also a privileged RBAC mutation). (Optional: also add an amplification/self-demotion guard — out of P0 scope; note in risks.)
- Do not change the AssignRoleRequest/DTO records.
- Verify imports: org.springframework.security.access.prepost.PreAuthorize is already imported (line 9).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/orgs/OrgService.java`
- Goal: apply the same rank check in addMember — actor cannot grant a role outranking themselves, and OWNER requires the actor to be OWNER. addMember currently takes (UUID orgId, String email, OrgMember.Role role) with no actor — must thread the actor's effective org role in.
- CHANGE addMember signature to: public OrgMember addMember(UUID orgId, String email, OrgMember.Role role, OrgMember.Role actorRole). (actorRole = the caller's role in this org; OrgController computes it; SUPER_ADMIN callers pass OWNER-equivalent, see OrgController change.)
- At the top of addMember, after the role==null check (lines 97-99), add the rank guard: if (actorRole == null) throw ApiException.forbidden(\"Not a member of this organization\"); if (rank(role) > rank(actorRole)) throw ApiException.forbidden(\"Cannot grant a role that outranks your own\"); if (role == OrgMember.Role.OWNER && actorRole != OrgMember.Role.OWNER) throw ApiException.forbidden(\"Only an OWNER can grant OWNER\");
- Add a private int rank(OrgMember.Role r) helper identical to OrgAccessChecker.rank: switch OWNER->4, ADMIN->3, MEMBER->2, VIEWER->1. (Do NOT try to share OrgAccessChecker's private method; duplicate the small switch or, better, add a public static int OrgMember.rank() — see risks; minimal P0 = duplicate the switch in OrgService.)
- Leave the rest of addMember unchanged (existing-member conflict check, save, audit).
- transferOwner already enforces OWNER-only at the controller via @orgAccess.hasRole(#orgId,'OWNER'); no change required there.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/orgs/OrgController.java`
- Add import: com.example.cp.common.AuthenticatedUser and com.example.cp.common.SecurityUtils (SecurityUtils already imported line 4).
- In addMember() (line 65-77), BEFORE calling orgService.addMember: resolve the actor's org role to pass as actorRole. AuthenticatedUser actor = SecurityUtils.requireUser(); OrgMember.Role actorRole = actor.superAdmin() ? OrgMember.Role.OWNER : orgService.roleOf(orgId, actor.userId()).orElseThrow(() -> com.example.cp.common.ApiException.forbidden(\"Not a member of this organization\"));
- Change the call to: OrgMember saved = orgService.addMember(orgId, body.email(), role, actorRole);
- Keep @PreAuthorize(\"@orgAccess.hasRole(#orgId, 'ADMIN')\") as the coarse gate; the new rank check in the service is the fine-grained guard (ADMIN can no longer add an OWNER, and cannot grant a role above their own).
- No DTO changes.

## Tests to add

- RbacController.assignRole: actor WITHOUT role.assign authority -> 403 (verifies @PreAuthorize switched from user.write to role.assign; a user.write-only token must now be rejected).
- RbacController.assignRole: actor with role.assign assigning roleCode='SUPER_ADMIN' -> 403 forbidden 'SUPER_ADMIN cannot be assigned via the API'.
- RbacController.assignRole: actor with role.assign assigning a role with is_system=TRUE -> 403 (covers the system-role block; align test data with whichever seed decision from risks #1 is taken).
- RbacController.assignRole privilege amplification: non-super actor whose org-scoped perms = {org.read,user.read} attempts to assign a role granting user.write -> 403 'Cannot grant authority you do not hold: user.write'. Verify it uses permissionsFor(actor,orgId) not the global SecurityContext authorities.
- RbacController.assignRole self-elevation: non-super actor assigns any role to their own userId -> 403 'Cannot assign roles to yourself'.
- RbacController.assignRole global scope: non-super actor with only org-scoped role.assign attempts orgId=null assignment -> 403 'Global role assignment requires platform authority'.
- RbacController.assignRole happy path: SUPER_ADMIN assigns a non-system, non-SUPER_ADMIN role globally -> 201 and user_role persisted; audit action rbac.role.assigned set.
- RbacController catalog read: unauthenticated/authenticated-without-rbac.read GET /api/v1/rbac/roles and /permissions -> 403 (verifies new @PreAuthorize('rbac.read')); with rbac.read -> 200.
- OrgService.addMember rank: actor ADMIN attempts to add member with role OWNER -> 403 'Only an OWNER can grant OWNER'.
- OrgService.addMember rank: actor ADMIN attempts to add member with role ADMIN (equal rank) -> allowed (rank(role) not > rank(actor)); actor ADMIN adding MEMBER/VIEWER -> allowed.
- OrgService.addMember rank: actor MEMBER (somehow passing controller gate via test) adding ADMIN -> 403 'Cannot grant a role that outranks your own'.
- OrgController.addMember: superAdmin actor treated as OWNER can add OWNER -> 201.
- OrgController.addMember: actor not a member of org and not super -> 403 'Not a member of this organization' (roleOf empty).
- Liquibase migration test (Testcontainers Postgres, dependency present in pom): after running changelog, SELECT confirms permissions role.assign and rbac.read exist and are linked to SUPER_ADMIN; role.assign linked to ORG_OWNER; rbac.read linked to ORG_OWNER and ORG_ADMIN. Re-running is idempotent (ON CONFLICT).

## Risks / cross-file notes

- CRITICAL seed implication: all 5 roles seeded in 02-rbac.sql have is_system=TRUE (SUPER_ADMIN, ORG_OWNER, ORG_ADMIN, ORG_MEMBER, VIEWER). A literal 'forbid is_system roles' block in RbacAuthorizationService.assertCanAssignRole will make EVERY currently-seeded role unassignable via /api/v1/rbac/users/{userId}/roles, effectively disabling the endpoint. Resolve before implementing: either (a) the intended model is that org/user role grants happen via OrgController (org_members) and the rbac endpoint is platform-only for future non-system roles — then the block is correct; or (b) migration 13 must set is_system=FALSE for ORG_OWNER/ORG_ADMIN/ORG_MEMBER/VIEWER (keeping SUPER_ADMIN system) so they remain assignable while SUPER_ADMIN stays blocked; or (c) special-case allow the 4 org roles in code while always blocking SUPER_ADMIN. Pick one explicitly; spec implements the literal block + SUPER_ADMIN block and flags this.
- Authorities scope mismatch: JwtAuthFilter builds AuthenticatedUser.authorities via authoritiesFor(userId, null) (GLOBAL only). So actor.hasAuthority(...) reflects global perms, NOT org-scoped. The amplification check therefore MUST use permissionService.permissionsFor(actor.userId(), orgId) to get the org-scoped superset, not actor.authorities(). Spec does this. If you instead trusted actor.authorities() for org-scoped grants you would wrongly deny org admins legitimate grants AND could miss org-scoped escalation.
- Role->permission code resolution: RolePermissionRepository.findByRoleId returns RolePermission(roleId, permissionId) with no code. Resolving codes requires N permissionRepository.findById calls or a new projection query. Recommend adding a JPQL method on PermissionRepository like @Query(\"select p.code from Permission p, RolePermission rp where rp.permissionId = p.id and rp.roleId = :roleId\") List<String> findCodesByRoleId(UUID roleId) to avoid N+1. If added, that is an extra edit to PermissionRepository — include it.
- OrgService.addMember signature change is a breaking compile change: the ONLY caller is OrgController.addMember (verified via grep — OrgService is referenced in OrgController, OrgAccessChecker, OrgService). OrgAccessChecker does not call addMember. Both call sites must be updated in the same change or the module won't compile. No other callers exist in main; tests are absent so no test callers to update.
- SUPER_ADMIN bypass: AuthenticatedUser.hasAuthority and OrgAccessChecker short-circuit true for superAdmin. assertCanAssignRole must still BLOCK assigning the SUPER_ADMIN role even for super admins-by-API per the design (no SUPER_ADMIN via API), so the SUPER_ADMIN/is_system block must run BEFORE the superAdmin amplification bypass. Spec orders it first (step 2 before step 4).
- Self-elevation edge: a superAdmin assigning to self is allowed by spec (step 5 excludes superAdmin) but they still cannot grant SUPER_ADMIN role (blocked step 2). Confirm this is acceptable; otherwise tighten.
- @PreAuthorize change from user.write to role.assign will break any existing API client/integration test that assigned roles using a user.write-only token. No such tests exist in-repo (no control-panel-api test sources), but external clients/Postman collections may need the new role.assign grant. Migration 13 grants role.assign to SUPER_ADMIN and ORG_OWNER only — accounts that previously relied on user.write to assign roles will now be denied until granted role.assign.
- Liquibase ordering: 13-rbac-assign-permissions.sql must be added to db.changelog-master.yaml include list or it will never run. Place between 12 and 99. Changeset id 'cp:13-rbac-permissions-seed' must be unique. ON CONFLICT (code) DO NOTHING / ON CONFLICT DO NOTHING keeps it idempotent like 12.
- OrgMember.Role has no SUPER_ADMIN/platform concept; mapping superAdmin actor to OWNER in OrgController is a pragmatic privilege model for the rank check. Verify product intent (a platform super admin acting in an org is treated as OWNER for grant purposes).
