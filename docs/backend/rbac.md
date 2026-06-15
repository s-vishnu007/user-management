# `com.example.cp.rbac` — Role-Based Access Control

> **Module:** `control-panel-api`  ·  **Package:** `com.example.cp.rbac`  ·  **Files documented:** 14

## Package overview

This package is the **authorization core** of the control panel. It owns the persistent
catalog of *roles* and *permissions*, the many-to-many wiring between them
(`role_permissions`), and the org-scoped assignment of roles to users (`user_roles`). At
runtime it resolves a user's effective **permission codes** into Spring Security
`GrantedAuthority` objects, and it enforces the *guards* that stop an actor from granting
or stripping authority they do not themselves hold (privilege amplification) or from
touching the protected, seeded *system* roles such as `SUPER_ADMIN`.

The package is intentionally split into three layers:

| Layer | Files | Job |
|-------|-------|-----|
| **JPA entities + IDs** | `Role`, `Permission`, `RolePermission`(+`RolePermissionId`), `UserRole`(+`UserRoleId`) | Map the five RBAC tables. |
| **Repositories** | `RoleRepository`, `PermissionRepository`, `RolePermissionRepository`, `UserRoleRepository` | Spring Data access, including the org-scope-aware custom queries. |
| **Services + web** | `PermissionService`, `AuthoritiesLoader`, `RbacAuthorizationService`, `RbacController` | Resolve effective permissions, adapt them to Spring Security, enforce assignment/removal guards, and expose the `/api/v1/rbac` API. |

### The mental model (read this first)

```
users ──< user_roles >── roles ──< role_permissions >── permissions
                 │                                            │
            org-scoped or                              code = the actual
            global (org_id NULL)                       authority string
```

* A **permission `code`** (e.g. `license.issue`, `role.assign`) is the unit of
  authorization. It is what `@PreAuthorize("hasAuthority('…')")` checks against. Roles are
  just *named bundles* of permission codes — Spring Security never sees a role, only the
  flattened set of codes a role expands to.
* A **role assignment** (`user_roles` row) is `(user_id, role_id, org_id)`. `org_id` may be
  `NULL`, which means a **global / platform-wide** grant; a non-null `org_id` scopes the
  grant to a single organization.
* **`SUPER_ADMIN`** is special: it is *not* expressed as a `user_roles` row in normal flow.
  It is a boolean (`users.is_super_admin`) that, when set, causes `AuthoritiesLoader` to
  return *every* permission code in the catalog plus the synthetic `SUPER_ADMIN` authority.

### How it fits the bigger picture

* On **every authenticated request**, `JwtAuthFilter` (package `com.example.cp.auth`)
  reloads the user, calls `AuthoritiesLoader.authoritiesFor(userId, null, superAdmin)` to
  compute the **global** authority set *fresh from the DB*, and stamps it onto the
  `AuthenticatedUser` principal in the `SecurityContext`. Note the deliberate `null` orgId:
  the SecurityContext authorities are **global-only**.
* `SsoSuccessHandler` (package `com.example.cp.sso`) calls the *same* `AuthoritiesLoader`
  when minting the post-SSO `cp_session` cookie's JWT, so the SSO and bearer-token paths
  produce identical authority sets.
* Every other controller in the system (`subscriptions`, `licenses`, `users`, `keys`, …)
  protects its endpoints with `@PreAuthorize("hasAuthority('<code>')")` against codes seeded
  by this package's Liquibase migrations.
* The **guard service** (`RbacAuthorizationService`) is the one place that re-queries
  *org-scoped* permissions (because the SecurityContext only carries global ones) to make
  the amplification check correct for org-scoped assignments.

### Where the catalog actually comes from (Liquibase, not Java)

The roles/permissions catalog is **seeded by migrations**, not by Java code. A new engineer
must look in `control-panel-api/src/main/resources/db/changelog/changes/` to understand what
exists:

* `02-rbac.sql` — creates the five tables, seeds the five **system roles**
  (`SUPER_ADMIN`, `ORG_OWNER`, `ORG_ADMIN`, `ORG_MEMBER`, `VIEWER`, all `is_system=TRUE`),
  the initial 13 permissions, and the role→permission grants. `SUPER_ADMIN` is granted
  `CROSS JOIN permissions` (everything).
* `12-additional-permissions.sql` — adds 14 more permission codes (`key.rotate`, `key.read`,
  `event.read`, `plan.read`, `plan.create`, `subscription.create/suspend/cancel`,
  `license.read`, `org.create`, `org.members.add/remove`, `api-key.create/delete`) and
  back-fills them onto `SUPER_ADMIN` via `CROSS JOIN … ON CONFLICT DO NOTHING`.
* `13-rbac-permissions.sql` — adds the two RBAC-management codes themselves — **`role.assign`**
  (assign/remove roles) and **`rbac.read`** (read the catalog) — and grants `role.assign` to
  `SUPER_ADMIN` + `ORG_OWNER`, and `rbac.read` to `SUPER_ADMIN` + `ORG_OWNER` + `ORG_ADMIN`.

> **Gotcha:** because new permission codes are auto-granted only to `SUPER_ADMIN` in
> migrations, adding a permission does **not** automatically give org admins access — you
> must add explicit `role_permissions` grants in a migration for `ORG_*` roles to see it.

The effective role→permission matrix seeded today:

| Permission code (category) | SUPER_ADMIN | ORG_OWNER | ORG_ADMIN | ORG_MEMBER | VIEWER |
|---|:--:|:--:|:--:|:--:|:--:|
| `org.read` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `org.write` | ✓ | ✓ | | | |
| `subscription.read` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `subscription.write` | ✓ | ✓ | ✓ | | |
| `license.issue` / `license.revoke` | ✓ | ✓ | ✓ | | |
| `user.invite` / `user.write` | ✓ | ✓ | ✓ | | |
| `user.read` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `audit.read` | ✓ | ✓ | ✓ | | ✓ |
| `sso.write` | ✓ | ✓ | | | |
| `apikey.write` | ✓ | ✓ | ✓ | | |
| `plan.write` | ✓ | | | | |
| `role.assign` | ✓ | ✓ | | | |
| `rbac.read` | ✓ | ✓ | ✓ | | |
| all `12-…` codes (`key.*`, `event.read`, `org.create`, …) | ✓ | | | | |

(`SUPER_ADMIN` always = everything, since it is computed in Java from `permissionRepository.findAll()`, not from `role_permissions`.)

---

## Entities and ID classes

### `Role.java`

**Responsibility:** JPA mapping of the `roles` table — a named bundle of permissions.

| Field | Column | Notes |
|-------|--------|-------|
| `id` | `id` (UUID PK) | Assigned by DB default `gen_random_uuid()` for seeded rows; **not** `@GeneratedValue`, so application-created roles would need an explicit id. |
| `code` | `code` (unique, len 64) | The stable machine identifier (`SUPER_ADMIN`, `ORG_OWNER`, …). Look-ups go through `code`, not `name`. |
| `name` | `name` | Human label. |
| `description` | `description` | Free text. |
| `isSystem` | `is_system` (NOT NULL) | **The protection flag.** `true` for the five seeded roles; the guard service refuses to assign/remove any role with `isSystem == true`. |

Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`. Note the getter for
the boolean is `isSystem()` (Lombok generates `isSystem()` for a `boolean isSystem` field),
which is exactly how `RbacAuthorizationService` calls it (`targetRole.isSystem()`).

**Collaborators:** read by `RoleRepository`, `RbacController` (look-up by code/id, DTO
projection via `RoleDto.from`), and `RbacAuthorizationService` (system-role + amplification
checks).

**Gotcha:** there is no JPA association from `Role` to `Permission`; the join is modelled as a
separate `RolePermission` entity keyed by raw UUIDs. Do not expect `role.getPermissions()`.

---

### `Permission.java`

**Responsibility:** JPA mapping of the `permissions` table — one row per authority code.

| Field | Column | Notes |
|-------|--------|-------|
| `id` | `id` (UUID PK) | DB-defaulted UUID. |
| `code` | `code` (unique, len **128**) | The authority string fed to `hasAuthority(...)`. |
| `name` / `description` | — | Human metadata. |
| `category` | `category` (len 64) | Grouping for UI (`org`, `license`, `rbac`, `keys`, …). |

`code` is the only field that matters for authorization; everything else is presentation.
**Collaborators:** `PermissionRepository`, `AuthoritiesLoader` (super-admin path enumerates all
permissions by code), `RbacAuthorizationService.permissionCodesForRole` (resolves a role's
codes), `RbacController.PermissionDto`.

---

### `RolePermissionId.java`

**Responsibility:** the composite-key class for `RolePermission` (`@IdClass`).

A plain `Serializable` POJO holding `roleId` + `permissionId`, with hand-written
`equals`/`hashCode` over both fields (`Objects.equals` / `Objects.hash`). JPA requires the
`@IdClass` to (a) be `Serializable`, (b) have a no-arg constructor, and (c) implement
`equals`/`hashCode` so it can be used as a map key in the persistence context — all satisfied
here. Uses a Java 17 pattern-matching `instanceof` in `equals`.

**Gotcha:** this is *not* a JPA `@Embeddable`; it pairs with `@IdClass(RolePermissionId.class)`
on the entity, so the entity declares the two id fields itself.

---

### `RolePermission.java`

**Responsibility:** JPA mapping of the `role_permissions` join table (a role grants a
permission). Composite PK `(roleId, permissionId)` via `@IdClass(RolePermissionId.class)`.

Two UUID fields (`roleId`, `permissionId`), both `@Id`, both `NOT NULL`. No surrogate key; no
navigation to `Role`/`Permission`. The DB layer adds `ON DELETE CASCADE` on both FKs and an
index on `permission_id`.

**Collaborators:** `RolePermissionRepository.findByRoleId` (used by the guard to expand a role
into its codes), and `deleteByRoleId` (declared, used when a role's permission set is rebuilt —
not exercised from this package directly).

---

### `UserRoleId.java`

**Responsibility:** composite-key class for `UserRole` — `(userId, roleId, orgId)`.

Same shape as `RolePermissionId` but **three** fields, the third being the nullable `orgId`.
`equals`/`hashCode` include all three via `Objects`, so `orgId == null` is a distinct key from
any concrete org id, and `Objects.hash`/`Objects.equals` handle the null cleanly.

**Concurrency / correctness note:** the **database** does *not* trust a nullable column in a
primary key, so `02-rbac.sql` adds a *generated* `org_id_key UUID GENERATED ALWAYS AS
COALESCE(org_id, '000…000') STORED` column and makes the real PK `(user_id, role_id,
org_id_key)`. This guarantees uniqueness even when `org_id` is `NULL` (Postgres would
otherwise allow duplicate `(user, role, NULL)` rows). The Java `UserRoleId` mirrors this by
treating `null` as a distinct value in `equals`/`hashCode`.

---

### `UserRole.java`

**Responsibility:** JPA mapping of the `user_roles` table — the actual grant of a role to a
user, optionally scoped to an org.

| Field | Column | Nullable | Meaning |
|-------|--------|:--:|---------|
| `userId` | `user_id` | no | grantee |
| `roleId` | `role_id` | no | granted role |
| `orgId` | `org_id` | **yes** | scope: `NULL` = global/platform, value = org-scoped |

All three are `@Id` (`@IdClass(UserRoleId.class)`). Built with Lombok `@Builder` (see
`RbacController.assignRole`).

**Collaborators:** `UserRoleRepository` (the org-scope-aware queries below); `RbacController`
creates and saves these rows. Read by `PermissionService` (joins through to permission codes).

**Gotcha:** JPA's `@Id` on the nullable `orgId` is unusual. It works for inserts/look-ups here
because all access goes through either `save()` (insert) or the *custom JPQL* queries in the
repository that special-case `orgId is null` — **not** through `findById(UserRoleId)`, which
would be fragile with a null id component. Prefer the repository's `countAssignment` /
`deleteAssignment` for existence/removal.

---

## Repositories

### `RoleRepository.java`

`JpaRepository<Role, UUID>` plus one derived query:

* `Optional<Role> findByCode(String code)` — primary look-up used by `RbacController.assignRole`
  (`AssignRoleRequest.roleCode` is a code, not an id). Removal uses `findById` because the
  delete endpoint takes the role's UUID in the path.

### `PermissionRepository.java`

`JpaRepository<Permission, UUID>` plus `Optional<Permission> findByCode(String code)`. `findAll()`
(inherited) is used by `AuthoritiesLoader` to enumerate the entire catalog for super-admins, and
`findById` by `RbacAuthorizationService.permissionCodesForRole` to translate a `RolePermission`'s
`permissionId` back into a `code`. `RbacController.listPermissions` uses the paginated `findAll`.

### `RolePermissionRepository.java`

`JpaRepository<RolePermission, RolePermissionId>` plus:

* `List<RolePermission> findByRoleId(UUID roleId)` — used by the guard to expand a role into its
  permission ids (then mapped to codes). This is the hot path of the amplification check.
* `void deleteByRoleId(UUID roleId)` — bulk-clear a role's grants (declared for role-editing
  flows; not invoked from within this package's controller).

### `UserRoleRepository.java`

`JpaRepository<UserRole, UserRoleId>` with the **org-scope-aware** custom queries that are the
heart of correct scoping. Study these carefully — they all encode the rule *"`org_id NULL` means
global"*:

```java
List<UserRole> findByUserId(UUID userId);   // all grants, any scope

@Query("select ur from UserRole ur where ur.userId = :userId "
     + "and (ur.orgId is null or ur.orgId = :orgId)")
List<UserRole> findByUserIdAndOrgIdOrGlobal(UUID userId, UUID orgId);

@Modifying
@Query("delete from UserRole ur where ur.userId = :userId and ur.roleId = :roleId "
     + "and ((:orgId is null and ur.orgId is null) or ur.orgId = :orgId)")
int deleteAssignment(UUID userId, UUID roleId, UUID orgId);   // returns rows deleted

@Query("select count(ur) from UserRole ur where ur.userId = :userId and ur.roleId = :roleId "
     + "and ((:orgId is null and ur.orgId is null) or ur.orgId = :orgId)")
long countAssignment(UUID userId, UUID roleId, UUID orgId);
```

Key semantics for a new engineer:

* **`findByUserIdAndOrgIdOrGlobal`** — *"give me every role this user holds that applies in this
  org"*: their org-scoped grants **plus** their global grants. This is the union you want when
  computing effective access inside an org.
* **`deleteAssignment` / `countAssignment`** — note the **exact-scope** matching:
  `((:orgId is null AND ur.orgId is null) OR ur.orgId = :orgId)`. Passing `orgId = null` targets
  *only* the global grant; passing a concrete org targets *only* that org's grant. This is
  deliberately stricter than `findByUserIdAndOrgIdOrGlobal` — you remove the *specific* grant you
  named, never the global one by accident, and vice versa.
* `deleteAssignment` returns the **affected row count**, which `RbacController.removeRole` uses to
  distinguish "removed" (`>0`) from "nothing to remove → 404" (`0`). `countAssignment` similarly
  drives the duplicate-assignment 409.

> **Gotcha:** `@Modifying` delete requires the method to be invoked inside a transaction —
> `removeRole` is `@Transactional`, so this is satisfied. Calling it outside a transaction would
> throw.

---

## Services

### `PermissionService.java`

**Responsibility:** resolve the **set of permission codes** a user effectively has, honoring the
org-scope rule, by walking `user_roles → role_permissions → permissions`. This is the
authoritative "what can this user do" query for *non*-super-admins.

```java
@Transactional(readOnly = true)
Set<String> permissionsFor(UUID userId, UUID orgId)
```

Behavior:

| `userId` | `orgId` | Result |
|----------|---------|--------|
| `null` | any | `Set.of()` (empty) — defensive; never NPEs on an anonymous principal. |
| non-null | `null` | Codes from **global** grants only (`ur.orgId is null`). |
| non-null | non-null | Codes from **global ∪ org-scoped** grants (`ur.orgId is null OR ur.orgId = :orgId`). |

Implementation notes & rationale:

* Uses raw **JPQL via `EntityManager`** (two query variants) rather than a derived repository
  method, because the three-way join (`Permission p, RolePermission rp, UserRole ur`) with a
  `distinct p.code` projection and the conditional org clause is clearest written by hand.
* `select distinct p.code` collapses the natural duplication when a user holds the same permission
  through multiple roles. Returned as a `LinkedHashSet` to keep insertion order deterministic
  (nice for tests/audit logs; not semantically required).
* `@SuppressWarnings("unchecked")` is present although the query is typed `String.class`; harmless.
* `readOnly = true` lets the DB/JPA optimize (no dirty-checking, possible read replica routing).

**Collaborators:**
* Called by `AuthoritiesLoader.authoritiesFor` for the non-super-admin authority computation
  (always with `orgId = null` from `JwtAuthFilter` / `SsoSuccessHandler` → SecurityContext is
  global-only).
* Called by `RbacAuthorizationService.assertCanAssign`/`assertCanRemove` **with the real `orgId`**
  to make the privilege-amplification check correct for org-scoped grants — this is the whole
  reason the service is reused there instead of reading the SecurityContext authorities.

**Why it matters / gotcha:** the SecurityContext authorities are global-only by construction. If
the guard naively trusted them, an `ORG_OWNER` who has org-scoped `role.assign` (but no *global*
authorities) could be wrongly blocked — or, worse, scope confusion could let a check pass against
the wrong org. Re-querying with the concrete `orgId` here is the fix.

---

### `AuthoritiesLoader.java`

**Responsibility:** the adapter between RBAC permission **codes** and Spring Security
`GrantedAuthority` objects, and the place where the **super-admin shortcut** lives.

```java
Set<String> authoritiesFor(UUID userId, UUID orgId, boolean superAdmin)
Collection<GrantedAuthority> toGrantedAuthorities(Set<String> codes)
```

* **`authoritiesFor`**
  * If `superAdmin`: returns **every** permission `code` in the catalog
    (`permissionRepository.findAll()` → `.getCode()`), collected into a `LinkedHashSet`, **plus**
    the synthetic string `"SUPER_ADMIN"`. So a super-admin literally holds every authority the
    system knows about, regardless of `role_permissions` rows. This is why the migrations'
    `CROSS JOIN` super-admin grants are belt-and-suspenders — Java already grants everything.
  * Otherwise: delegates to `permissionService.permissionsFor(userId, orgId)`.
* **`toGrantedAuthorities`** — maps each code string to a `SimpleGrantedAuthority`. Null-safe
  (returns empty list for a null set). No `ROLE_` prefix is added: authorities are bare codes, and
  `@PreAuthorize` uses `hasAuthority('code')` (not `hasRole`), which matches exactly.

**Collaborators / callers:**
* `JwtAuthFilter.doFilterInternal` — on every request: `authoritiesFor(userId, null, superAdmin)`
  then `toGrantedAuthorities(...)`, building the `AuthenticatedUser` principal. **Always recomputed
  fresh from the DB**, deliberately ignoring any authorities frozen into the JWT, so a
  just-revoked permission or a now-suspended account cannot ride a still-valid token.
* `SsoSuccessHandler.issueSessionCookie` — computes the same set to embed in the freshly minted
  `cp_session` JWT.

**Gotcha:** `authoritiesFor` is the *only* component that adds the `"SUPER_ADMIN"` authority
string. Downstream, `AuthenticatedUser.superAdmin` (a boolean derived from `users.is_super_admin`)
is what `hasAuthority(...)` actually short-circuits on, so the literal `"SUPER_ADMIN"` authority is
mostly a marker/diagnostic — but it *is* present in the set, so any `hasAuthority('SUPER_ADMIN')`
checks elsewhere would also succeed.

---

### `RbacAuthorizationService.java`

**Responsibility:** the **centralized privilege-escalation guard** — the security-critical heart of
this package. Both the assign and remove endpoints route their authorization decision through here
so the rules cannot drift apart. Throws `ApiException` (mapped to 400/401/403 by the global handler)
on any violation; returns `void` on success.

Dependencies: `PermissionService` (org-scoped actor perms), `RolePermissionRepository` +
`PermissionRepository` (expand a role into codes).

#### `void assertCanAssign(AuthenticatedUser actor, Role targetRole, UUID targetUserId, UUID orgId)`

Ordered gates (order is load-bearing — read the comments in source, they call this out):

| # | Gate | Throws | Why |
|---|------|--------|-----|
| 1 | `actor == null` | 401 Unauthorized | Defense in depth; controller already required auth. |
| 2a | `targetRole == null` | 400 Bad Request | Role is required. |
| 2b | `code == "SUPER_ADMIN"` | 403 | `SUPER_ADMIN` is never assignable via the API. |
| 2c | `targetRole.isSystem()` | 403 | The five seeded roles are out-of-band only. |
| 3 | `orgId == null && !superAdmin && !hasAuthority("role.assign")` | 403 | A *global* grant requires platform authority — even though `@PreAuthorize` already required `role.assign`, re-asserted because super-admin bypasses `hasAuthority` and the org path differs. |
| 4 | For each code the role grants, `!actorPerms.contains(code)` | 403 | **Privilege amplification:** you cannot grant authority you don't hold. `actorPerms = permissionService.permissionsFor(actor.userId(), orgId)` — org-scoped, *not* the global SecurityContext set. |
| 5 | `targetUserId == actor.userId()` (and not super-admin) | 403 | **Self-elevation guard:** you cannot assign roles to yourself. |

**Critical ordering detail:** the system-role block (gate 2) runs **before** the super-admin
amplification bypass (gates 4/5 are skipped when `actor.superAdmin()`). The consequence is that
**even a super-admin cannot assign `SUPER_ADMIN` or any `is_system` role through this endpoint** —
the only way those roles change is out-of-band (DB/migration). That is the explicit design intent
documented in the source comments.

The super-admin bypass applies only to gates 3, 4, and 5 (scope, amplification, self-elevation), all
of which are guarded by `!actor.superAdmin()`. So a super-admin can freely assign any *non-system*
role to anyone (including, technically, the self-elevation gate is skipped for them — but since
super-admins already hold everything, self-assignment is a no-op risk).

#### `void assertCanRemove(AuthenticatedUser actor, Role targetRole, UUID orgId)`

Mirrors `assertCanAssign` gates 1–4 **exactly** (auth, system-role block, global-scope authority,
amplification) but **omits the self-elevation guard (gate 5)** and takes no `targetUserId`. Rationale
spelled out in source: removing a role is *de-escalation*, never escalation, and a user removing their
own non-system role is legitimate, so the self guard is intentionally absent.

Why mirror the amplification check on *removal*? So removal cannot be used as an indirect lever:
without it, an actor could strip a role granting permissions they have no authority over (e.g. to
disrupt another admin). The check says "you can only remove a role whose every permission you also
hold."

#### `Set<String> permissionCodesForRole(UUID roleId)`

Helper (also `public` for unit testing) that expands a role into its set of permission `code`s:
`rolePermissionRepository.findByRoleId(roleId)` → for each, `permissionRepository.findById(permissionId)`
→ collect `code` into a `LinkedHashSet`. Null-safe on `roleId`. Missing permissions (orphan
`role_permissions` rows) are silently skipped via `Optional.ifPresent`.

> **Performance gotcha:** this is an N+1 pattern — one `findByRoleId` then one `findById` per
> permission. Fine for the small per-role permission counts here, but do not reuse it in a hot loop
> over many roles.

**Collaborators:** invoked only by `RbacController.assignRole` (→ `assertCanAssign`) and
`removeRole` (→ `assertCanRemove`). Reads `AuthenticatedUser` (from `com.example.cp.common`) for
`superAdmin()`, `hasAuthority(...)`, `userId()`. Builds `ApiException`s via the static factories.

---

## Web layer

### `RbacController.java`  — `@RestController`, base path `/api/v1/rbac`

**Responsibility:** the REST surface for reading the catalog and managing user-role assignments.
Thin: it validates input, sets up audit context, delegates the security decision to
`RbacAuthorizationService`, and performs the DB mutation. Constructor-injects `RoleRepository`,
`PermissionRepository`, `UserRoleRepository`, `RbacAuthorizationService`.

| Method | Route | `@PreAuthorize` | Returns |
|--------|-------|-----------------|---------|
| `listRoles` | `GET /roles` | `hasAuthority('rbac.read')` | `PagedResponse<RoleDto>` |
| `listPermissions` | `GET /permissions` | `hasAuthority('rbac.read')` | `PagedResponse<PermissionDto>` |
| `assignRole` | `POST /users/{userId}/roles` | `hasAuthority('role.assign')` | `201` `UserRoleDto` |
| `removeRole` | `DELETE /users/{userId}/roles/{roleId}?orgId=` | `hasAuthority('role.assign')` | `204` |

#### `listRoles` / `listPermissions`

Read-only catalog browsing. Pagination via `PageRequestParams.of(page, size, null)` (a common
helper) → `repository.findAll(pageable).map(Dto::from)` → wrapped by `PagedResponse.from`. Guarded
by `rbac.read` so only `SUPER_ADMIN`/`ORG_OWNER`/`ORG_ADMIN` can list the catalog.

#### `assignRole(@PathVariable UUID userId, @Valid @RequestBody AssignRoleRequest body)`  — `@Transactional`

Control flow (the **ordering is security-relevant**):

```
1. role = roleRepository.findByCode(body.roleCode())  → 404 "Role not found" if absent
2. actor = SecurityUtils.requireUser()                → 401 if no principal
3. AuditContext.set("rbac.role.assigned")             ← set BEFORE the guard …
   AuditContext.setTarget("user_role", "<user>:<role>[:<org>]")
   AuditContext.putPayload("role_code", role.code)
4. rbacAuthz.assertCanAssign(actor, role, userId, body.orgId())   ← may throw 400/401/403
5. if countAssignment(user, role, org) > 0 → 409 "Role already assigned"
6. userRoleRepository.save(UserRole.builder()…)        ← insert the grant
7. 201 + UserRoleDto(userId, roleId, roleCode, orgId)
```

* **Why audit context is set before the guard (step 3 before 4):** an `@AfterThrowing` audit advice
  (the audit interceptor) needs an `action` present in `AuditContext` so that a *denied* or *failed*
  assignment is recorded too — not just successes. The target id encodes scope as
  `user:role[:org]` so the audit row is unambiguous. `AuditContext` is a `ThreadLocal` cleared by
  `JwtAuthFilter`'s `finally`.
* **409 conflict** is checked *after* authorization (you must be allowed to assign before we tell you
  it already exists) and uses the exact-scope `countAssignment`.
* `@Transactional` ensures the existence check + insert are atomic; the DB's generated `org_id_key`
  unique PK is the ultimate backstop against a duplicate (would surface as a constraint violation if
  two requests raced past the `countAssignment` check).

#### `removeRole(@PathVariable UUID userId, @PathVariable UUID roleId, @RequestParam(required=false) UUID orgId)`  — `@Transactional`

```
1. role = roleRepository.findById(roleId)             → 404 if absent
2. actor = SecurityUtils.requireUser()                → 401
3. AuditContext.set("rbac.role.removed") + setTarget(...)   ← before the guard, like assign
4. rbacAuthz.assertCanRemove(actor, role, orgId)      ← system-role / scope / amplification
5. removed = userRoleRepository.deleteAssignment(user, roleId, orgId)
6. if removed == 0 → 404 "Role assignment not found"   else 204 No Content
```

* Note removal looks the role up **by id** (path param) whereas assign looks it up **by code**
  (request body) — two different but intentional input shapes.
* `deleteAssignment` is exact-scope (see repository). Passing no `orgId` removes only the global
  grant. A removed-count of 0 → 404 (idempotency-ish: removing a non-existent grant is "not found",
  not a silent success).

#### DTOs / request records (nested, public)

* **`AssignRoleRequest(@NotBlank String roleCode, UUID orgId)`** — request body. `@Valid` on the
  param triggers bean validation; a blank `roleCode` → 400 before any handler logic.
* **`RoleDto(id, code, name, description, boolean isSystem)`** with `static from(Role)` — note it
  exposes `isSystem`, so clients can grey out system roles in the UI.
* **`PermissionDto(id, code, name, description, category)`** with `static from(Permission)`.
* **`UserRoleDto(userId, roleId, roleCode, orgId)`** — the assignment confirmation payload.

**Collaborators / callers:** invoked by the admin UI's role-management screens. Depends on
`com.example.cp.common` helpers (`ApiException`, `AuthenticatedUser`, `SecurityUtils`, `AuditContext`,
`PageRequestParams`, `PagedResponse`). The `@PreAuthorize` codes are evaluated against the authorities
that `AuthoritiesLoader` placed on the principal earlier in the filter chain.

---

## End-to-end: how an assignment is authorized

```
HTTP POST /api/v1/rbac/users/{u}/roles  { roleCode, orgId }
      │
      ▼
JwtAuthFilter ── reload user ── AuthoritiesLoader.authoritiesFor(u, null, superAdmin)
      │            (fresh, global-only authority codes → GrantedAuthority list)
      ▼
@PreAuthorize("hasAuthority('role.assign')")   ← coarse gate on global authorities
      │
      ▼
RbacController.assignRole ── set AuditContext ──► RbacAuthorizationService.assertCanAssign
                                                        │
                 ┌──────────────────────────────────────┘
                 ▼
        gate 2: block SUPER_ADMIN / is_system roles  (even for super-admins)
        gate 3: global scope requires role.assign / super-admin
        gate 4: PermissionService.permissionsFor(actor, orgId)  ← org-scoped amplification check
        gate 5: no self-assignment
                 │ (all pass → return)
                 ▼
        countAssignment > 0 ? → 409 : save(UserRole) → 201
```

The two-tier design (`@PreAuthorize` *coarse* gate on global authorities, then
`RbacAuthorizationService` *fine* gate that re-queries org-scoped perms and enforces system-role /
amplification / self-elevation) is the key thing to internalize: **the annotation is necessary but
not sufficient; the service is where the real escalation defenses live.**

## Gotchas checklist for new engineers

* **SecurityContext authorities are global-only.** Anything org-scoped must go through
  `PermissionService.permissionsFor(userId, orgId)`. Don't read the principal's authority set for an
  org decision.
* **System roles are immutable via the API** — including for super-admins. Change them in
  migrations, not at runtime.
* **Adding a permission code does not grant it to anyone but `SUPER_ADMIN`** unless your migration
  adds explicit `role_permissions` rows for the `ORG_*` roles.
* **`org_id NULL` = global.** The custom queries encode this; the DB enforces uniqueness via the
  generated `org_id_key` column. Don't try to `findById(UserRoleId)` with a null org component —
  use `countAssignment`/`deleteAssignment`.
* **Audit action is set before the authz check** on purpose, so denials are audited. Don't "tidy" it
  to run after the guard.
* **Authorities are recomputed every request** by `JwtAuthFilter`; revocations take effect
  immediately, not at token expiry. The JWT's embedded authorities are effectively advisory.
* `permissionCodesForRole` is N+1 — fine here, dangerous if reused at scale.
