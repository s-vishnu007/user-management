# `com.example.cp.orgs` — Organizations, Membership & Org-Scoped Authorization

## Module overview

This package is the **tenancy backbone** of the control panel. An `Organization` is the unit of tenancy: customers, subscriptions, licenses, API keys and audit records all hang off an org. The package owns three concerns and keeps them deliberately small and focused:

1. **The org + membership data model** — two JPA entities (`Organization`, `OrgMember`) plus the composite-key class (`OrgMemberId`) and their Spring Data repositories (`OrganizationRepository`, `OrgMemberRepository`).
2. **The business rules around who may join an org and at what role** — `OrgService`, which enforces slug validation, role-rank guards (you cannot grant or remove a role at or above your own), and the critical **last-OWNER protection** that prevents an org from being left ownerless.
3. **Authorization plumbing** — `OrgAccessChecker` (the SpEL bean `@orgAccess`) that `@PreAuthorize` expressions on `OrgController` (and other controllers) call to ask *"is the current principal a member of / does it have role X in this org?"*.

The package implements a **two-layer authorization model** that is worth internalizing before reading the files:

| Layer | Mechanism | Example | Who grants it |
|-------|-----------|---------|----------------|
| **Global RBAC permission** | Spring authorities like `org.write`, checked with `hasAuthority(...)` | "may this principal create *any* org?" | seeded in `02-rbac.sql` via roles → permissions |
| **Per-org membership role** | `OrgMember.Role` (OWNER/ADMIN/MEMBER/VIEWER), checked with `@orgAccess.hasRole(#orgId, 'ADMIN')` | "is this principal an ADMIN *of this specific org*?" | rows in `org_members` |

A request frequently passes through **both** layers. E.g. `POST /api/v1/orgs/{orgId}/members` requires the global authority to even reach the method (via `@orgAccess.hasRole`, which itself does a membership lookup) **and** then `OrgService.addMember` re-checks the actor's *effective role* against the role being granted. The double-check is intentional: the SpEL layer is coarse ("are you at least an ADMIN here?") and the service layer is fine ("you, an ADMIN, may not grant OWNER").

### How it fits the bigger picture

Almost every other feature module is **org-scoped** and relies on this package as the gatekeeper:

- **`SecurityUtils` / `AuthenticatedUser`** (in `com.example.cp.common`) supply the current principal; `OrgAccessChecker` reads them and consults `OrgService` to resolve the principal's role in a given org.
- **Subscriptions, licenses, API keys, usage, audit** are all keyed by `org_id`; their controllers use the same `@orgAccess.isMember(#orgId)` / `@orgAccess.hasRole(#orgId, 'ADMIN')` SpEL idioms documented here. This package is therefore the single definition of "tenant isolation" for human principals.
- **Super-admins** (`AuthenticatedUser.superAdmin()`) bypass org membership entirely — they are treated as OWNER everywhere. This is enforced consistently in both `OrgAccessChecker` and `OrgController`/`OrgService`.
- The **audit subsystem** is fed through `AuditContext` ThreadLocal calls that `OrgService` makes (`org.created`, `org.member.added`, `org.member.removed`, `org.owner.transferred`); an interceptor downstream turns those into audit-log rows.

A note from the project history: this package was a focus of the security audit. Several remediations are baked directly into the code and called out in Javadoc with tags like **(P3)** — specifically the **last-OWNER count-then-delete race**, the **actor-vs-target rank guard on removal**, and **honest pagination totals**. Those are highlighted per-file below because they are exactly the gotchas a new engineer is likely to "simplify" and thereby reintroduce.

---

## File catalog

### `Organization.java` — the tenant aggregate root

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/Organization.java`

**Responsibility:** JPA entity mapping the `organizations` table. This is the root of a tenant.

**Type:** `@Entity public class Organization` (Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`).

**Nested enum:** `Organization.Status { ACTIVE, SUSPENDED, DELETED }` — persisted as a string via `@Enumerated(EnumType.STRING)`. The DB mirrors this with a `CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))` constraint, so adding an enum value requires a matching migration or inserts will be rejected at the DB layer.

**Fields / column mapping:**

| Field | Column | Notes |
|-------|--------|-------|
| `UUID id` | `id` (PK) | Application-assigned (not DB-generated in practice). `OrgService` sets it via `Ids.newId()` → a **time-ordered (v7) UUID** (`UuidCreator.getTimeOrderedEpoch()`). Time-ordering keeps B-tree index inserts append-friendly. The DDL has `DEFAULT gen_random_uuid()` as a fallback but the app always supplies the id. |
| `long version` | `version` | **`@Version` optimistic lock.** Added in migration `17-optimistic-locking.sql` (existing rows default to 0). Hibernate increments on every UPDATE; a stale concurrent write throws `OptimisticLockException` rather than silently losing data. Relevant on multi-instance deployments. |
| `String slug` | `slug` (unique, len 64) | URL-safe tenant handle. Uniqueness is enforced at **both** the DB (`UNIQUE`) and the service (`existsBySlug`) layers; the DB constraint is the real race-safe guard. |
| `String name` | `name` | Display name. |
| `Status status` | `status` | Lifecycle. Note: `OrgService` only ever sets `ACTIVE` on create; SUSPENDED/DELETED transitions are not implemented in this package (no soft-delete or suspend method here — a gotcha if you expect lifecycle management to live with the entity). |
| `OffsetDateTime createdAt / updatedAt` | `created_at` / `updated_at` | Set in `createOrg`; **note `updatedAt` is never touched after creation by this package** — there is no org-update endpoint here, so it always equals `createdAt` for orgs created through the API. |

**Collaborators:** persisted/loaded by `OrganizationRepository`; built and saved by `OrgService.createOrg`; projected to `OrgController.OrgDto` for the wire.

**Gotcha:** there is **no JPA relationship** to `OrgMember` (no `@OneToMany`). Membership is modeled as an independent table queried explicitly through `OrgMemberRepository`. This is deliberate — it avoids cascade surprises and lazy-loading N+1s — but means you must never expect `org.getMembers()` to exist.

---

### `OrgMember.java` — the membership join entity

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrgMember.java`

**Responsibility:** JPA entity for the `org_members` join table linking a user to an org with a role. This is the per-tenant authorization record.

**Type:** `@Entity @IdClass(OrgMemberId.class) public class OrgMember` (Lombok builders/accessors as above).

**Nested enum — the role lattice:** `OrgMember.Role { OWNER, ADMIN, MEMBER, VIEWER }`. Declaration order matters conceptually but **the code never relies on enum ordinal for ranking** — ranking is done explicitly via a `switch` (see `rank(...)` in `OrgService` and `OrgAccessChecker`). The numeric ranks are `OWNER=4 > ADMIN=3 > MEMBER=2 > VIEWER=1`. Persisted as string with a DB `CHECK` constraint matching these four values.

**Composite primary key:** `(orgId, userId)`. Declared with two `@Id` fields plus `@IdClass(OrgMemberId.class)`. A user therefore has **at most one row per org** — uniqueness of membership is the PK itself. `OrgService.addMember` still pre-checks for an existing row to return a friendly 409 instead of a DB constraint violation.

**Fields / columns:**

| Field | Column | Notes |
|-------|--------|-------|
| `UUID orgId` | `org_id` (PK part) | FK → `organizations(id)` `ON DELETE CASCADE` (deleting an org wipes its memberships). |
| `UUID userId` | `user_id` (PK part) | FK → `users(id)` `ON DELETE CASCADE` (deleting a user removes them from all orgs). Indexed (`idx_org_members_user`) to make `findByUserId` fast — that backs "list my orgs". |
| `Role role` | `role` | The per-org role. |
| `OffsetDateTime addedAt` | `added_at` | When the membership was created. |

**Gotcha:** unlike `Organization`, `OrgMember` has **no `@Version` column** — membership rows are not optimistically locked. The concurrency hazard this would otherwise create (two threads racing to delete the last owner) is instead closed at the *query* level by `deleteMemberUnlessLastOwner` (see `OrgMemberRepository`). Don't "add a version field to be safe" expecting it to fix the last-owner race; the conditional delete is what does that.

---

### `OrgMemberId.java` — the composite-key class

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrgMemberId.java`

**Responsibility:** The `Serializable` ID class JPA requires for `OrgMember`'s composite `@IdClass`. It is also the key type for `OrgMemberRepository extends JpaRepository<OrgMember, OrgMemberId>`, so `findById(new OrgMemberId(org, user))` works.

**Type:** `public class OrgMemberId implements Serializable`.

**Members:** mutable `UUID orgId` / `UUID userId` with a no-arg ctor (required by JPA), a 2-arg ctor, getters/setters, and hand-written `equals`/`hashCode`.

**Why it exists / contract:** JPA's `@IdClass` contract mandates:
- field **names and types must exactly match** the `@Id` fields on `OrgMember` (`orgId`, `userId` — both `UUID`) — they do.
- `Serializable` + correct `equals`/`hashCode` so the persistence context can use it as a map key and the first-level cache can dedupe entities.

`equals` uses a Java 16 pattern `instanceof` (`o instanceof OrgMemberId other`) and compares both UUIDs with `Objects.equals`; `hashCode` is `Objects.hash(orgId, userId)`. Both fields participate, consistent with the PK.

**Gotcha:** this class is written by hand rather than Lombok-generated, and it is **not** a `record` (records can't be `@IdClass` targets cleanly because JPA wants a no-arg ctor and mutable setters). If you regenerate it, keep field names byte-for-byte aligned with `OrgMember`'s `@Id` fields or Hibernate will fail to bootstrap with an opaque mapping error.

---

### `OrganizationRepository.java` — org persistence

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrganizationRepository.java`

**Responsibility:** Spring Data JPA repository for `Organization`.

**Type:** `public interface OrganizationRepository extends JpaRepository<Organization, UUID>`.

**Derived query methods:**

| Method | Generated query | Used by |
|--------|-----------------|---------|
| `Optional<Organization> findBySlug(String slug)` | `... where slug = ?` | Lookups by handle (not used inside this package's current code paths but part of the public contract for slug-addressed access elsewhere). |
| `boolean existsBySlug(String slug)` | `select count ... where slug = ?` (existence) | `OrgService.createOrg` pre-flight uniqueness check → friendly 409 before hitting the DB `UNIQUE`. |

Inherited from `JpaRepository`: `findById`, `save`, etc. — `OrgService.get` uses `findById(...).orElseThrow(notFound)`.

**Gotcha:** `existsBySlug` is **advisory, not authoritative** — between the `existsBySlug` check and the `save`, a concurrent create with the same slug could slip in. The DB `UNIQUE` constraint is the real guard and would surface as a `DataIntegrityViolationException`. The `existsBySlug` check exists only to turn the common case into a clean `409 Conflict` ("Slug already taken").

---

### `OrgMemberRepository.java` — membership persistence + the atomic last-OWNER delete

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrgMemberRepository.java`

**Responsibility:** Spring Data JPA repository for `OrgMember`, keyed by `OrgMemberId`. Hosts both the routine membership lookups and the **security-critical conditional delete**.

**Type:** `public interface OrgMemberRepository extends JpaRepository<OrgMember, OrgMemberId>`.

**Derived query methods:**

| Method | Purpose / caller |
|--------|------------------|
| `List<OrgMember> findByOrgId(UUID orgId)` | List all members of an org (`OrgService.listMembers`, `transferOwner`). |
| `List<OrgMember> findByUserId(UUID userId)` | "What orgs am I in?" — backs `OrgService.listOrgsForUser` (uses the `idx_org_members_user` index). |
| `Optional<OrgMember> findByOrgIdAndUserId(UUID orgId, UUID userId)` | Single membership lookup — backs `isMember`, `roleOf`, and the pre-checks in `addMember`/`removeMember`. |
| `long countByOrgIdAndRole(UUID orgId, Role role)` | Count members in a role. **Present but currently unused by `OrgService`** — the last-OWNER logic was moved into the atomic delete below; this method is a leftover/utility. Don't reintroduce a `count → delete` flow with it (that's the race that was fixed). |
| `void deleteByOrgIdAndUserId(UUID orgId, UUID userId)` | Unconditional delete. **Also not used by `OrgService`'s remove path** — `removeMember` uses the guarded delete instead. Calling this directly bypasses the last-OWNER protection, so prefer `deleteMemberUnlessLastOwner`. |

**The remediation method — `deleteMemberUnlessLastOwner`:**

```java
@Modifying
@Query("""
    delete from OrgMember m
    where m.orgId = :orgId and m.userId = :userId
      and not (
        m.role = :ownerRole
        and (select count(o) from OrgMember o where o.orgId = :orgId and o.role = :ownerRole) <= 1
      )
    """)
int deleteMemberUnlessLastOwner(@Param("orgId") UUID orgId,
                                @Param("userId") UUID userId,
                                @Param("ownerRole") OrgMember.Role ownerRole);
```

- **What it does:** deletes the `(orgId, userId)` row **unless** that row is an OWNER *and* it is the only OWNER left. The owner-count subquery is evaluated **inside the same DELETE statement**, so there is no window between "count owners" and "delete".
- **Return value:** number of rows removed. `0` means *either* the row didn't exist *or* it was the last OWNER and was deliberately left in place. The caller (`OrgService.removeMember`) disambiguates these two `0` cases by re-checking the role it loaded earlier.
- **Why it exists (P3 fix):** the original code did `countByOrgIdAndRole(...) ; if (>1) deleteByOrgIdAndUserId(...)`. Two concurrent removals of two different owners could each read "2 owners", each decide it's safe, and both delete — leaving the org with **zero** owners (unrecoverable without admin intervention). Folding the count into the delete makes the database evaluate the invariant atomically per row.
- **Why bind the role as a parameter:** keeps the JPQL free of a fully-qualified enum literal (`com.example.cp.orgs.OrgMember.Role.OWNER`), which JPQL handles awkwardly; the caller passes `OrgMember.Role.OWNER`.

**Concurrency caveat to understand:** the subquery + delete is atomic *for a single concurrent removal*, but at the default READ COMMITTED isolation two transactions each deleting a *different* owner could still, in the worst interleaving, both see two owners and both commit, leaving one — which is still safe (≥1 owner preserved). The invariant the query guarantees is "**you can never delete the *last* owner**", which is exactly the property that matters. It does not guarantee "at most one owner is removed at a time".

---

### `OrgAccessChecker.java` — the `@orgAccess` SpEL authorization bean

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrgAccessChecker.java`

**Responsibility:** A Spring `@Component` exposed under the bean name **`orgAccess`** so it can be referenced from method-security SpEL: `@PreAuthorize("@orgAccess.isMember(#orgId)")`. It bridges the **current security principal** (`SecurityUtils.currentUser()`) to the **per-org role model** (`OrgService.roleOf`). This is the single point where "is this caller allowed to touch this tenant?" is answered for human users.

**Wiring prerequisite:** `@EnableMethodSecurity(prePostEnabled = true)` in `com.example.cp.auth.SecurityConfig` is what makes `@PreAuthorize` (and these `@bean.method()` SpEL references) active. Without it these checks are silently no-ops — worth knowing if you ever see authorization "not firing".

**Type:** `@Component("orgAccess") public class OrgAccessChecker`. Constructor-injects `OrgService` (so all DB lookups go through the transactional read methods).

**Public methods:**

| Method | Returns `true` when… |
|--------|----------------------|
| `boolean isMember(UUID orgId)` | the caller is a **super-admin** *or* has any membership row in `orgId`. Unauthenticated → `false`. |
| `boolean hasRole(UUID orgId, String roleName)` | the caller is a super-admin *or* their org role **rank ≥ the required role's rank**. So `hasRole(org, "ADMIN")` is true for both ADMIN and OWNER (rank ≥ 3). Unknown `roleName` or non-member → `false`. |
| `boolean isOwnerOrAdmin(UUID orgId)` | convenience alias for `hasRole(orgId, "ADMIN")`. |

**Control flow of `hasRole` (the important one):**

```
currentUser? ──no──▶ false
   │yes
superAdmin? ──yes──▶ true            (super-admins bypass membership)
   │no
roleOf(orgId, me)? ──empty──▶ false  (not a member at all)
   │present
valueOf(roleName) ── invalid ─▶ false (defensive: bad SpEL literal)
   │valid
return rank(myRole) >= rank(required)
```

**Key design points & gotchas:**

- **Super-admin short-circuit appears in every method.** A super-admin is treated as an org OWNER everywhere without needing an `org_members` row. This is the same convention `OrgController`/`OrgService` follow (they pass `actorRole = OWNER, superAdmin = true`). If you add a new access method here, replicate the `me.get().superAdmin()` early-return or you'll accidentally lock super-admins out.
- **Fail-closed on bad input.** An unknown `roleName` string (e.g. a typo in a `@PreAuthorize` expression) is caught (`IllegalArgumentException` from `Role.valueOf`) and returns `false`, not an exception. Safer default, but it means a misspelled role in SpEL silently denies rather than erroring loudly — double-check role literals.
- **Rank is hardcoded, duplicated.** The private `rank(Role)` switch here is **a verbatim copy** of the one in `OrgService`. Both must stay in sync. This duplication is a known smell; if you reorder the lattice, change both.
- **It is a *read*-side check.** It calls `OrgService.roleOf`/`isMember`, each `@Transactional(readOnly = true)`. Calling it from `@PreAuthorize` means a DB round-trip (one membership lookup) per guarded request. Acceptable, but be aware that endpoints with multiple `@orgAccess` checks pay multiple lookups.
- **`#orgId` binding.** The `#orgId` in the SpEL refers to the controller method's `@PathVariable UUID orgId` by name — the parameter must literally be named `orgId` for the expression to resolve.

---

### `OrgService.java` — org/membership business logic & the security guards

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrgService.java`

**Responsibility:** The transactional service that owns all org and membership mutations and reads. This is where the **slug validation**, **role-rank guards**, **last-OWNER protection**, and **audit annotations** live.

**Type:** `@Service public class OrgService`. Constructor-injects `OrganizationRepository`, `OrgMemberRepository`, and `UserRepository` (the last to resolve a member by email).

**Constant:** `SLUG_PATTERN = ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$` — lowercase alphanumerics and dashes, must start/end with an alphanumeric, total length 3–64. This is duplicated as a Bean Validation `@Pattern` on `OrgController.CreateOrgRequest`, giving defense in depth (request-layer rejection *and* service-layer rejection).

**Methods:**

#### `createOrg(String slug, String name, UUID ownerUserId)` — `@Transactional`
- Validates slug against `SLUG_PATTERN` → `400`; validates non-blank name → `400`; checks `existsBySlug` → `409`.
- Builds an `Organization` with a fresh `Ids.newId()` (time-ordered UUID), `status = ACTIVE`, `createdAt = updatedAt = now`.
- Saves the org, then **if `ownerUserId != null`** creates an `OrgMember` row with `Role.OWNER` for that user. The null-guard matters: the method can create an "orphan" org with no members. The controller always passes the authenticated actor's id, so via the API every org is born with exactly one OWNER — but a programmatic caller could create an ownerless org.
- Emits audit: `action = org.created`, target = `organization:<id>`.
- **Returns** the saved `Organization`.

#### `get(UUID orgId)` — `@Transactional(readOnly = true)`
- `findById(...).orElseThrow(notFound)`. The canonical "load or 404" used by `addMember` and the controller's `GET /{orgId}`.

#### `listOrgsForUser(UUID userId)` — `@Transactional(readOnly = true)`
- `findByUserId` → for each membership, `findById` the org, filter out nulls, collect.
- **Gotcha / N+1:** this is an explicit per-membership `findById` loop — one query per org a user belongs to. Fine for the expected small fan-out (a user is in a handful of orgs) but not built for a user in thousands of orgs. The `.filter(o -> o != null)` defends against a dangling membership whose org was deleted out from under it (shouldn't happen given `ON DELETE CASCADE`, but the guard is cheap).

#### `listMembers(UUID orgId)` / `isMember(orgId, userId)` / `roleOf(orgId, userId)` — `@Transactional(readOnly = true)`
- Thin pass-throughs to the repository. `roleOf` maps the optional membership to its `Role`. These three are the read primitives `OrgAccessChecker` and `OrgController` lean on.

#### `addMember(UUID orgId, String email, Role role, Role actorRole)` — `@Transactional`
The membership-grant guard ladder, in order:

1. `role == null` → `400` ("Role is required").
2. `actorRole == null` → `403` ("Not a member of this organization") — the caller isn't in the org.
3. **Rank guard:** `rank(role) > rank(actorRole)` → `403` ("Cannot grant a role that outranks your own"). An ADMIN (rank 3) cannot grant OWNER (4); they *can* grant ADMIN/MEMBER/VIEWER (≤3).
4. **OWNER-grant guard:** `role == OWNER && actorRole != OWNER` → `403` ("Only an OWNER can grant OWNER"). Strictly speaking redundant with the rank guard for ADMINs (since OWNER outranks ADMIN), but it makes the OWNER-specific rule explicit and would still hold if ranks were ever flattened.
5. `get(orgId)` → `404` if org missing.
6. `userRepository.findByEmail(email)` → `404` ("User with that email not found"). Members are added **by email**, not by id — the user must already exist in the system (no implicit invite/provisioning here).
7. Duplicate check `findByOrgIdAndUserId` present → `409` ("User is already a member").
8. Build + save the `OrgMember` (with `addedAt = now`).
9. Audit: `org.member.added`, target `org_member:<orgId>:<userId>`, payload `role`.

**Note on `actorRole`:** the *caller* (`OrgController.addMember`) is responsible for computing the actor's effective role — super-admins are passed `OWNER`. The service trusts that value. So the service-layer guards assume `actorRole` already reflects the super-admin elevation.

#### `private int rank(Role)`
- `OWNER=4, ADMIN=3, MEMBER=2, VIEWER=1`. Duplicated in `OrgAccessChecker` (keep in sync).

#### `removeMember(UUID orgId, UUID userId, Role actorRole, boolean superAdmin)` — `@Transactional`
The remove-side guard ladder:

1. Load the target membership `findByOrgIdAndUserId` → `404` ("Member not found") if absent.
2. **If not super-admin:**
   - `actorRole == null` → `403` (caller not a member).
   - **Strict rank guard:** `rank(target.role) >= rank(actorRole)` → `403` ("Cannot remove a member whose role equals or outranks your own"). This is *stricter* than `addMember`'s guard: removal requires the target to be **strictly below** the actor. Consequences:
     - an ADMIN cannot remove an OWNER (target 4 ≥ actor 3) ✔ blocks privilege-equalization,
     - an ADMIN cannot remove a **peer ADMIN** (3 ≥ 3) ✔,
     - **an OWNER cannot remove another OWNER** (4 ≥ 4) — peer-owner removal must go through `transferOwner`/self-removal logic, not this path. Worth flagging: there is no "I am OWNER, remove the other OWNER" path except by transferring ownership first.
   - Super-admins skip the whole rank block (they pass `actorRole = OWNER, superAdmin = true`, but the `if (!superAdmin)` gate means even the rank check is bypassed — a super-admin can remove anyone, subject only to the last-OWNER rule below).
3. **Atomic last-OWNER delete:** `deleteMemberUnlessLastOwner(orgId, userId, OWNER)`.
4. Interpret the return:
   - `removed == 0 && target.role == OWNER` → `400` ("Cannot remove the last OWNER").
   - `removed == 0 && target.role != OWNER` → `404` ("Member not found") — the row vanished concurrently between step 1 and step 3.
   - `removed == 1` → success; audit `org.member.removed`, target `org_member:<orgId>:<userId>`.

**Why the structure (P3 fix):** the rank guard closes a privilege-escalation/lateral-movement hole (an ADMIN demoting/removing owners), and the conditional delete closes the count-then-delete race that could zero out owners. Both were audit findings; do not collapse the conditional delete back into a count-then-delete, and do not relax `>=` to `>` in the rank guard (that would re-permit peer-ADMIN removal).

#### `transferOwner(UUID orgId, UUID newOwnerUserId)` — `@Transactional`
- Loads the prospective new owner's membership → `404` ("New owner is not a member") if the target isn't already in the org. **You can only transfer ownership to an existing member.**
- Iterates all members; every *current* OWNER that isn't the new owner is **demoted to ADMIN** and saved. This supports orgs that (somehow) have multiple owners — all are collapsed down.
- Promotes the new owner's row to `OWNER` and saves.
- Audit: `org.owner.transferred`, target `organization:<id>`, payload `new_owner_user_id`.

**Gotchas in `transferOwner`:**
- **No idempotency/no-op guard:** if `newOwnerUserId` is *already* the sole OWNER, the loop demotes nobody and re-sets them to OWNER — harmless, but be aware it's not explicitly handled.
- **Not guarded by the rank/last-owner machinery** — it's its own flow. Because it always *ends* with exactly one OWNER, it can never violate the last-OWNER invariant, which is why it doesn't need `deleteMemberUnlessLastOwner`.
- **Authorization is entirely at the controller** (`@orgAccess.hasRole(#orgId, 'OWNER')`). The service does not re-check that the caller is an OWNER. So if you call `transferOwner` from new code, you must enforce the OWNER check yourself — the service trusts its caller here (unlike `addMember`/`removeMember`, which take an `actorRole` and self-guard).

**Audit collaboration (all mutating methods):** `OrgService` writes to `AuditContext` (a ThreadLocal) rather than persisting audit rows itself. A downstream `AuditInterceptor` (outside this package) reads `currentAction()/currentTarget*/currentPayload()` after the request and writes the audit log. Implication: an audit row is only produced if the request completes through that interceptor; the service just *annotates* the thread.

---

### `OrgController.java` — REST endpoints + DTOs

**Path:** `control-panel-api/src/main/java/com/example/cp/orgs/OrgController.java`

**Responsibility:** REST controller under `/api/v1/orgs`. Owns request/response DTOs, the two-layer auth wiring (global authority + `@orgAccess` SpEL), super-admin elevation, and an in-memory pagination helper. It is intentionally thin — all rules live in `OrgService`.

**Type:** `@RestController @RequestMapping("/api/v1/orgs") public class OrgController`, constructor-injecting `OrgService`.

**Endpoints:**

| Verb & path | Auth guard | Body | Success | Delegates to |
|-------------|-----------|------|---------|--------------|
| `POST /` | `@PreAuthorize("hasAuthority('org.write')")` | `CreateOrgRequest` | `201` + `OrgDto` | `createOrg(slug, name, currentUserId)` |
| `GET /` | *(none beyond authentication)* | — | `200` + `PagedResponse<OrgDto>` | `listOrgsForUser(currentUserId)` |
| `GET /{orgId}` | `@PreAuthorize("@orgAccess.isMember(#orgId)")` | — | `200` + `OrgDto` | `get(orgId)` |
| `GET /{orgId}/members` | `@PreAuthorize("@orgAccess.isMember(#orgId)")` | — | `200` + `PagedResponse<OrgMemberDto>` | `listMembers(orgId)` |
| `POST /{orgId}/members` | `@PreAuthorize("@orgAccess.hasRole(#orgId, 'ADMIN')")` | `AddMemberRequest` | `201` + `OrgMemberDto` | `addMember(orgId, email, role, actorRole)` |
| `DELETE /{orgId}/members/{userId}` | `@PreAuthorize("@orgAccess.hasRole(#orgId, 'ADMIN')")` | — | `204` | `removeMember(orgId, userId, actorRole, superAdmin)` |
| `POST /{orgId}/transfer-owner` | `@PreAuthorize("@orgAccess.hasRole(#orgId, 'OWNER')")` | `TransferOwnerRequest` | `204` | `transferOwner(orgId, newOwnerUserId)` |

**The `create` endpoint** uses the **global RBAC permission** `org.write` (not an org role — you can't be a member of an org that doesn't exist yet). Per `02-rbac.sql`, `org.write` is granted to `SUPER_ADMIN` and `ORG_OWNER` roles. The newly-created org's first OWNER is the actor (`SecurityUtils.currentUserId()`), so the creator is auto-enrolled as OWNER via `createOrg`.

**The `listMine` endpoint** has **no `@PreAuthorize`** — it is intentionally scoped to *the caller's own* memberships (`currentUserId`), so it is self-isolating; any authenticated principal sees only their orgs. (Authentication itself is enforced globally by the security filter chain; `currentUserId()` throws `401` if unauthenticated.)

**Super-admin elevation pattern (in `addMember` and `removeMember`):**
```java
AuthenticatedUser actor = SecurityUtils.requireUser();
OrgMember.Role actorRole = actor.superAdmin()
        ? OrgMember.Role.OWNER
        : orgService.roleOf(orgId, actor.userId())
                .orElseThrow(() -> ApiException.forbidden("Not a member of this organization"));
```
- Super-admins are mapped to `OWNER` so the service-layer rank guards treat them as top-rank.
- Non-super actors must have a membership; otherwise `403`. Note this re-derives the role *after* the `@orgAccess.hasRole` SpEL check already passed — a second DB lookup, but it gives the service the precise role for its own guards (and for `removeMember`, the `superAdmin` boolean too).
- The `removeMember` flow additionally forwards `actor.superAdmin()` so the service can bypass the rank block for super-admins entirely.

**Role parsing in `addMember`:** `OrgMember.Role.valueOf(body.role().toUpperCase())`, wrapped to throw `400` ("Invalid role; must be OWNER, ADMIN, MEMBER, or VIEWER") on a bad value. So role is accepted case-insensitively on the wire but validated to the enum.

**`paginate(List<T> all, Pageable)` — the honest-totals helper (P3 fix):**
```java
int total = all.size();
int from = min(pageNumber * pageSize, total);
int to   = min(from + pageSize, total);
return PagedResponse.of(all.subList(from, to), total, pageNumber, pageSize);
```
- Used by `listMine` and `listMembers`, which derive their data from membership lists that **cannot be served by a single Spring Data `Pageable` query** (they fan out across orgs / are already fully loaded).
- **Why it exists:** previously the `PagedResponse` envelope reported a `total` that didn't match the windowed slice — the API "lied" about pagination. This computes the **true total** from the full in-memory list and slices a real window. The `long` casts in the `Math.min` calls prevent `int` overflow when `pageNumber * pageSize` is large.
- **Gotcha:** because the *entire* list is materialized before slicing, this is only safe for **inherently bounded** collections (a user's org list; an org's member list). It would be a memory hazard if reused for an unbounded collection — don't copy this pattern for, say, audit logs. Page size is already capped at `PageRequestParams.MAX_SIZE` (200) and defaults to 20.

**DTOs (nested records, all public):**

| Record | Shape | Validation / mapping notes |
|--------|-------|----------------------------|
| `CreateOrgRequest(String slug, String name)` | request | `slug`: `@NotBlank @Size(3..64) @Pattern(...)` (same regex as `SLUG_PATTERN`); `name`: `@NotBlank @Size(max 255)`. Bean Validation runs first via `@Valid`, so malformed input is rejected before `OrgService` even sees it. |
| `AddMemberRequest(String email, String role)` | request | `email`: `@NotBlank @Email`; `role`: `@NotBlank` (the *enum* validity is checked in the handler, not by an annotation). |
| `TransferOwnerRequest(UUID newOwnerUserId)` | request | `@NotNull`. |
| `OrgDto(id, slug, name, status, createdAt, updatedAt)` + `from(Organization)` | response | `status` is null-safe-mapped to its `.name()`. Note **`version` is deliberately not exposed** — clients never see the optimistic-lock counter. |
| `OrgMemberDto(orgId, userId, role, addedAt)` + `from(OrgMember)` | response | `role` null-safe-mapped to `.name()`. Exposes the composite key parts and the role. |

**Gotchas / things a new engineer should know about the controller:**
- **Two-layer auth is real and intentional.** The SpEL guard (`@orgAccess.hasRole(#orgId, 'ADMIN')`) lets *any* ADMIN/OWNER reach `addMember`/`removeMember`; the *service* then enforces the finer "can't grant/remove a role ≥ your own" rule. Removing the service-layer guard because "the SpEL already checks ADMIN" would reopen the privilege-escalation finding (an ADMIN granting OWNER).
- **`#orgId` / `#userId` SpEL binding depends on parameter names** matching the path variables; don't rename the method params.
- **`OrgDto.from` reads `getStatus()`/`getCreatedAt()` etc.** straight off the entity inside the transactional read — fine because all fields are eagerly mapped and there are no lazy associations.

---

## Cross-cutting flows (read this to connect the dots)

**Adding a member — full request path:**
```
POST /api/v1/orgs/{orgId}/members  {email, role}
  │
  ├─ Spring Security filter chain → authenticates → AuthenticatedUser principal
  ├─ @PreAuthorize @orgAccess.hasRole(#orgId,'ADMIN')
  │     OrgAccessChecker.hasRole → super-admin? OR rank(myRole) >= ADMIN(3)
  ├─ @Valid on AddMemberRequest (email/role non-blank, email well-formed)
  ├─ OrgController.addMember:
  │     parse role enum (400 if bad)
  │     compute actorRole (super-admin → OWNER, else roleOf or 403)
  └─ OrgService.addMember (TX):
        guards: role!=null, actorRole!=null, rank(role)<=rank(actor),
                OWNER only by OWNER, org exists, user exists, not already member
        save OrgMember; AuditContext.set("org.member.added")
  → 201 OrgMemberDto
```

**Removing the last owner — why it's safe:**
```
removeMember → load target → (non-super) rank(target) < rank(actor) required
            → deleteMemberUnlessLastOwner(org,user,OWNER)  [atomic count-in-delete]
            → removed==0 && target was OWNER → 400 "Cannot remove the last OWNER"
```

**Super-admin treatment (consistent everywhere):** super-admin ⇒ treated as org OWNER, bypasses membership lookups in `OrgAccessChecker`, and bypasses the rank block in `removeMember`. The only rule a super-admin *cannot* break is the last-OWNER invariant (the conditional delete applies to everyone).

## Known smells / maintenance hazards (summary)
- **`rank(Role)` is duplicated** in `OrgService` and `OrgAccessChecker` — keep in sync.
- **`countByOrgIdAndRole` and `deleteByOrgIdAndUserId` are unused** by the safe path; using them directly bypasses last-OWNER protection.
- **`Organization.updatedAt` is never updated** post-create (no update endpoint in this package).
- **`createOrg` can create an ownerless org** when `ownerUserId == null` (not reachable via the controller, but possible programmatically).
- **`OrgMember` has no `@Version`** — concurrency is handled by the conditional delete, not optimistic locking; don't assume otherwise.
- **`transferOwner` self-trust:** authorization for it lives only in the controller's `@PreAuthorize`; the service does not re-check OWNER.
