# `com.example.cp.security` — Tenant-Isolation Access Control

## Module overview

This package is tiny by line count but load-bearing for the whole product's multi-tenant security model. It contains exactly **one** source file — `TenantAccessChecker.java` — registered as the Spring bean named `tenantAccess`. That bean is the single, centralized authority that answers the question *"is the current principal allowed to touch a resource that belongs to org X?"* Every resource-scoped `@PreAuthorize` SpEL expression across the REST controllers delegates the *cross-tenant* part of its decision to this bean (e.g. `@tenantAccess.canReadSubscription(#id)`, `@tenantAccess.canManageOrg(#orgId)`, `@tenantAccess.canIngestUsageForJti(#body.jti())`).

The design goal is to make a cross-org Insecure-Direct-Object-Reference (IDOR) **structurally impossible**: the checker always resolves the *target resource's owning org* from the database and authorizes against that, and it deliberately refuses to consult any global authority (scope) that could otherwise act as a tenant bypass. This is the direct remediation of the audit's "tenant-isolation IDOR family" P0 blocker (see MEMORY: Audit 2026-06-01). The package is intentionally minimal so that the entire tenant-isolation rulebook lives in one auditable place.

> **How it fits the bigger picture.** Authentication produces an `AuthenticatedUser` principal (a human user, or an API-key principal bound to exactly one org). RBAC (`com.example.cp.rbac`) decides *what kind of action* a principal may perform via coarse authorities/scopes like `subscription.read` or `billing.read`. `TenantAccessChecker` is the orthogonal second half: it decides *which tenant's data* the principal may apply those actions to. Controllers compose the two with SpEL `and`/`or` so that, for example, "you have the `subscription.read` scope **AND** the subscription you asked for belongs to an org you're a member of." A sibling bean, `@orgAccess` (`OrgAccessChecker` in the `orgs` package), serves a similar purpose for endpoints keyed directly on an org id; the two overlap conceptually and are contrasted near the end of this doc.

---

## File: `control-panel-api/src/main/java/com/example/cp/security/TenantAccessChecker.java`

### Responsibility

A stateless Spring `@Component("tenantAccess")` that exposes a family of boolean predicate methods for use in method-security SpEL. Each predicate takes an identifier for *some* resource (an org id, a subscription id, or a license `jti`), resolves the org that owns that resource, and returns whether the current security-context principal is allowed to **read** or **manage** (write) resources in that org. It is **default-deny**: any `null` argument, missing resource, or unresolvable org yields `false`.

The bean name matters: `@Component("tenantAccess")` is what makes `@tenantAccess.<method>(...)` resolvable inside `@PreAuthorize`. Renaming the bean would silently break every annotation that references it (SpEL bean references fail closed by raising an evaluation error, which Spring Security treats as access denied — so the failure mode is "everything 403s," not "everything opens," which is the safe direction).

### The fixed authorization order (the core invariant)

Every public predicate funnels into one of two primitives — `canAccessOrg(orgId)` (read) or `canManageOrg(orgId)` (write/manage) — and both apply the **same three-tier ladder** in the **same fixed order**:

| Tier | Principal | Read (`canAccessOrg`) | Write/manage (`canManageOrg`) |
|------|-----------|------------------------|-------------------------------|
| 1 | `super_admin` | **allow** (only global bypass) | **allow** (only global bypass) |
| 2 | API-key principal | allow **iff** `apiKeyOrgId == targetOrg` | **deny** (keys have no write scope by default) |
| 3 | Human user | allow iff an `OrgMember` row exists for `(targetOrg, userId)` | allow iff that member's role rank ≥ `ADMIN` |

The flow for a single primitive, in pseudocode:

```
canAccessOrg(orgId):
    if orgId == null:                 return false          # default-deny
    u = currentUser() or null
    if u == null:                     return false          # unauthenticated
    if u.superAdmin():                return true           # tier 1
    if u.isApiKey():                  return orgId == u.apiKeyOrgId()   # tier 2
    return isMemberOf(u, orgId)                              # tier 3 (any role)
```

```
canManageOrg(orgId):
    if orgId == null:                 return false
    u = currentUser() or null
    if u == null:                     return false
    if u.superAdmin():                return true           # tier 1
    if u.isApiKey():                  return false          # tier 2: keys never manage
    return isManagerOf(u, orgId)                            # tier 3 (role >= ADMIN)
```

**Why this exact order, and why no authority short-circuit:** The class Javadoc is explicit that there is *deliberately* no `subscription.read` / `license.issue` / `usage.read` short-circuit inside this bean. If the checker honored a global authority, a principal granted (say) a platform-wide `subscription.read` scope would be able to read *any* tenant's subscriptions — exactly the IDOR the audit flagged. By ignoring authorities entirely for *org resolution*, the checker guarantees the tenant boundary holds regardless of how generously scopes are handed out. Authorities are still enforced — but at the endpoint level, AND-composed in the `@PreAuthorize` SpEL (`hasAuthority('x') and @tenantAccess.canX(...)`), never inside this bean.

---

### Public methods

The public surface is grouped into three bands: org-level, subscription-scoped, and license(`jti`)-scoped. All of them ultimately reduce to `canAccessOrg`/`canManageOrg`.

#### Org-level primitives

**`boolean canAccessOrg(UUID orgId)`**
The read primitive (tier ladder above). Used directly by endpoints that already have the org id in the path/param, and indirectly by every subscription/license read predicate. Notable points:
- A `super_admin` is the **only** principal that crosses org boundaries.
- For an API key, "membership" is defined as *being the key's single bound org* (`orgId.equals(u.apiKeyOrgId())`). There is no notion of an API key that spans orgs — this is what stops a leaked/over-scoped key from reaching another tenant.
- For a human, **any** org role (including `VIEWER`) satisfies read access, because `isMemberOf` only checks for the *existence* of an `OrgMember` row, not its rank.

**`boolean canManageOrg(UUID orgId)`**
The write/manage primitive. Differs from `canAccessOrg` in two ways: (1) API-key principals are **always denied** ("API keys have no write scope by default -> default deny for management"), and (2) human users must hold a role of rank ≥ `ADMIN`. The API-key denial is a coarse, conservative default — automated machine identities can read their own org but cannot, e.g., issue or void licenses or rotate webhooks, even if their org membership would otherwise imply it. (Endpoints that genuinely need a key to perform a privileged action route around this with a different, narrower scope — e.g. usage ingest below uses `canIngestUsageForJti`, a *read*-level check, plus the explicit `usage.ingest` authority.)

#### Subscription-scoped predicates

These all resolve `subscriptionId -> orgId` (via `resolveOrgForSubscription`) and then defer to the org primitive:

| Method | Delegates to | Used by `@PreAuthorize` on |
|--------|--------------|-----------------------------|
| `canReadSubscription(UUID subscriptionId)` | `canAccessOrg` | `LicenseController` list/issue-history, `SubscriptionController.get`, `UsageIngestController.listUsage` |
| `canWriteSubscription(UUID subscriptionId)` | `canManageOrg` | `SubscriptionController` update/cancel/etc. |
| `canReadSubscriptionInOrg(UUID orgId)` | `canAccessOrg` (passthrough) | `SubscriptionController.list` (org-keyed) |
| `canWriteSubscriptionInOrg(UUID orgId)` | `canManageOrg` (passthrough) | `SubscriptionController.create` (org-keyed) |
| `canIssueLicenseForSubscription(UUID subscriptionId)` | `canWriteSubscription` → `canManageOrg` | `LicenseController.issue` |
| `canReadUsageForSubscription(UUID subscriptionId)` | `canReadSubscription` → `canAccessOrg` | (semantic alias; usage read endpoint currently uses `canReadSubscription` directly) |

Two of these — `canReadSubscriptionInOrg` / `canWriteSubscriptionInOrg` — are thin passthroughs that exist purely for **readability at the call site**. When a controller method already carries the org id (e.g. `POST /orgs/{orgId}/subscriptions`), calling `canWriteSubscriptionInOrg(#orgId)` reads better and skips the unnecessary DB lookup that a `subscriptionId` form would require. Functionally they are `canManageOrg`/`canAccessOrg`.

`canIssueLicenseForSubscription` being aliased to `canWriteSubscription` encodes a security decision worth calling out: **issuing a license is treated as a write against the subscription's org**, so only super-admins or OWNER/ADMIN humans of the owning org may mint a license. An org-bound API key cannot issue licenses (because the chain bottoms out in `canManageOrg`, which denies keys).

#### License (`jti`)-scoped predicates

These resolve `jti -> subscription -> org` (via `resolveOrgForJti`) and then defer:

| Method | Delegates to | Used by `@PreAuthorize` on |
|--------|--------------|-----------------------------|
| `canReadLicenseByJti(String jti)` | `canAccessOrg` | `LicenseController` get/CRL-status, `LicenseHeartbeatController` activations + (operator) heartbeat path |
| `canRevokeLicenseByJti(String jti)` | `canManageOrg` | `LicenseController.revoke` (composed with `or hasAuthority('SUPER_ADMIN')`) |
| `canIngestUsageForJti(String jti)` | `canAccessOrg` | `UsageIngestController.ingest`, `LicenseHeartbeatController.heartbeat` (machine path) |

A subtle but deliberate choice: **`canIngestUsageForJti` is a *read*-level check (`canAccessOrg`), not a manage check.** If it used `canManageOrg`, every API key would be denied (tier 2) and the whole machine-to-machine usage/heartbeat flow would be impossible. Instead, ingest is gated by `canAccessOrg` (the key must be bound to the license's owning org) **combined at the endpoint** with the explicit `hasAuthority('usage.ingest')` scope. So the effective rule for ingest is: *"a principal carrying the `usage.ingest` scope whose bound org owns the license."* This is why the JTI band needs a separate "ingest" predicate rather than reusing the read one — the *name* documents intent even though the body is identical to `canIngestUsageForJti == canAccessOrg(resolvedOrg)`.

---

### Internal resolution helpers

**`Optional<UUID> resolveOrgForSubscription(UUID subscriptionId)`** *(package-private)*
`subscriptionId == null` → `Optional.empty()`. Otherwise `subRepo.findById(subscriptionId).map(Subscription::getOrgId)`. A missing subscription yields empty → the caller maps empty to `false` (default-deny). Package-private (not `private`) so unit tests in the same package can exercise resolution independently of the SpEL plumbing.

**`Optional<UUID> resolveOrgForJti(String jti)`** *(package-private)*
`jti == null || jti.isBlank()` → empty. Otherwise `tokenRepo.findByJti(jti).map(LicenseToken::getSubscriptionId).flatMap(this::resolveOrgForSubscription)`. This is a **two-hop** resolution (token → subscription → org); if either hop is missing, the whole thing is empty → deny. Note `findByJti` is the *non-locking* finder (read-only authorization probe), distinct from the pessimistic `findByJtiForUpdate` the heartbeat seat-enforcement path uses — authorization must not take row locks.

**`boolean isMemberOf(AuthenticatedUser u, UUID orgId)`** *(private)*
`u.userId() == null` → `false` (guards a malformed principal). Otherwise existence of `memberRepo.findByOrgIdAndUserId(orgId, u.userId())`. Any role counts.

**`boolean isManagerOf(AuthenticatedUser u, UUID orgId)`** *(private)*
Same null guard, then loads the `OrgMember` and requires `rank(role) >= rank(ADMIN)`. So `OWNER` (4) and `ADMIN` (3) pass; `MEMBER` (2) and `VIEWER` (1) fail.

**`int rank(OrgMember.Role r)`** *(private)*
Maps the role enum to a comparable rank: `OWNER=4, ADMIN=3, MEMBER=2, VIEWER=1`, and `null → 0`. The `null → 0` branch means a member row with a missing/unknown role can never satisfy any threshold — fail-closed. This rank table is **duplicated** in `OrgAccessChecker.rank(...)` (see gotchas).

---

### Key fields, dependencies, and collaborators

Constructor-injected repositories (the bean is otherwise stateless and thread-safe; a single instance serves all concurrent requests):

| Field | Type | Purpose |
|-------|------|---------|
| `subRepo` | `SubscriptionRepository` | `findById` → `Subscription.getOrgId()` for subscription/license resolution |
| `memberRepo` | `OrgMemberRepository` | `findByOrgIdAndUserId(orgId, userId)` for human membership/role lookups |
| `tokenRepo` | `LicenseTokenRepository` | `findByJti(jti)` → `LicenseToken.getSubscriptionId()` for license resolution |

**The current principal** is *not* injected; it is read on every call from the thread-bound `SecurityContextHolder` via `SecurityUtils.currentUser()`. Relevant collaborators:

- **`com.example.cp.common.SecurityUtils`** — `currentUser()` returns `Optional<AuthenticatedUser>`: it reads `SecurityContextHolder.getContext().getAuthentication()` and returns the principal only when it is authenticated *and* the principal object is an `AuthenticatedUser` (otherwise empty). This is why an unauthenticated or oddly-typed principal makes every predicate return `false`.
- **`com.example.cp.common.AuthenticatedUser`** — a record carrying `userId`, `email`, `superAdmin`, `authorities`, `grantedAuthorities`, `apiKey`, `apiKeyOrgId`. The checker only ever reads `superAdmin()`, `isApiKey()`, `apiKeyOrgId()`, and `userId()`. Crucially it **never** reads `authorities` — reinforcing the "no authority short-circuit" invariant. The record has a backward-compatible 5-arg constructor that defaults `apiKey=false, apiKeyOrgId=null` for human principals.
- **`com.example.cp.orgs.OrgMember`** + its `Role` enum (`OWNER, ADMIN, MEMBER, VIEWER`) — the membership row; `@IdClass(OrgMemberId.class)` composite key of `(orgId, userId)`.
- **Resource entities** `Subscription` (`getOrgId`) and `LicenseToken` (`getSubscriptionId`) — supply the owning-org linkage.

**Who calls this bean** (every `@tenantAccess.*` reference across the API):

- `BillingController` — reads gated by `hasAuthority('billing.read') and @tenantAccess.canAccessOrg(#orgId)`; writes (generate/issue/void) by `@tenantAccess.canManageOrg(#orgId)`.
- `ApiKeyController` — create/revoke by `canManageOrg(#orgId)`, list by `canAccessOrg(#orgId)`.
- `WebhookController` — all mutating endpoints by `canManageOrg(#orgId)`.
- `SubscriptionController` — list/create by the `*InOrg(#orgId)` passthroughs; get/update/cancel/etc. by `canReadSubscription(#id)` / `canWriteSubscription(#id)`.
- `LicenseController` — issue by `canIssueLicenseForSubscription(#subId)`; get/history by `canReadLicenseByJti`/`canReadSubscription`; revoke by `canRevokeLicenseByJti(#jti) or hasAuthority('SUPER_ADMIN')`.
- `LicenseHeartbeatController` — heartbeat by `(hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#jti)) or @tenantAccess.canReadLicenseByJti(#jti)`; activations by `canReadLicenseByJti(#jti)`.
- `UsageIngestController` — ingest by `hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#body.jti())`; usage report by `(subscription.read or usage.read) and canReadSubscription(#subId)`.
- `ScimUserController` — uses `@tenantAccess.canAccessOrg(@scimOrg.callerOrgId())` (see SCIM note below).

---

### Worked example — how a single request is decided

`POST /api/v1/usage/ingest` with body `{ "jti": "abc...", "events": [...] }`, called by an API key bound to org `A`:

```
@PreAuthorize("hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#body.jti())")
                       │                                         │
        (1) RBAC: does the key carry usage.ingest? ─────────────┘ (2) tenant check below
```

1. SpEL binds `#body` to the deserialized `IngestRequest`; `#body.jti()` extracts the license id.
2. `canIngestUsageForJti("abc...")` → `resolveOrgForJti` does `findByJti` → token → `subscriptionId` → `findById` → `getOrgId()` = org `B` (say).
3. `canAccessOrg(B)`: principal is an API key, not super-admin → tier 2 → `B.equals(apiKeyOrgId=A)` → **false**.
4. Result: access denied (403). The org-A key cannot ingest against an org-B license, *even with a valid `usage.ingest` scope*. If the token resolved to org `A`, step 3 returns true and the controller proceeds.

This is the IDOR defense in action: the authorization decision is anchored to the *target resource's* org, fetched from the DB, never to anything the caller asserts.

---

### Gotchas and things a new engineer must know

- **Default-deny everywhere.** Null inputs, missing rows, malformed principals, and unauthenticated contexts all return `false`. Never "optimize" a guard away — each one is a deliberate fail-closed branch.
- **Authorities are intentionally invisible here.** Do not add a `if (u.hasAuthority("subscription.read")) return true` style short-circuit to this bean to "simplify" an endpoint. That would reopen the cross-tenant IDOR. Endpoint-level authority checks belong in the `@PreAuthorize` SpEL, AND-composed with the tenant predicate.
- **API keys can read their own org but cannot manage it.** `canManageOrg` hard-denies API keys. If a machine integration legitimately needs to perform a write, it must go through a purpose-built predicate (like the ingest path) that uses a *read*-level org check plus a narrow explicit scope — not by loosening `canManageOrg`.
- **Two DB round-trips on the JTI path.** `resolveOrgForJti` does `findByJti` then `findById`. Because `@PreAuthorize` runs before the method body, these queries execute on *every* call to a JTI-scoped endpoint. They're indexed point lookups (jti is `unique`), but be aware authorization is not free.
- **The `rank()` table is duplicated** in `OrgAccessChecker`. If role semantics ever change (e.g. a new role, or a different threshold for "manage"), both copies must change in lockstep. They are currently identical (`OWNER=4 … VIEWER=1`), but `OrgAccessChecker.rank` lacks the `null → 0` guard that `TenantAccessChecker.rank` has (the former takes its role from `roleOf(...)` which is already non-null when present).
- **SpEL bean-name coupling.** The string `tenantAccess` in `@Component("tenantAccess")` is an API contract with dozens of annotations. Treat it as public surface.
- **`super_admin` is the lone escape hatch.** It bypasses *all* tenant checks in both read and write primitives. Granting `super_admin` is therefore equivalent to granting full cross-tenant access; it must be tightly controlled by RBAC. (Note also `LicenseController.revoke` additionally honors `hasAuthority('SUPER_ADMIN')` as an `or` branch — a belt-and-suspenders path for platform operators revoking across tenants.)

---

### Relationship to the sibling `@orgAccess` (`OrgAccessChecker`) predicate

These two beans look similar and a newcomer will conflate them. The distinction:

| | `@tenantAccess` (`TenantAccessChecker`, this package) | `@orgAccess` (`OrgAccessChecker`, `com.example.cp.orgs`) |
|--|--|--|
| Resolves org from | the **target resource** (subscription/jti) *or* a direct org id | a **direct org id** only (always `#orgId`) |
| Knows about API keys | **Yes** — tier 2 binds to `apiKeyOrgId` | **No** — only super-admin bypass + human membership |
| Backing lookup | repositories directly (`memberRepo`, `subRepo`, `tokenRepo`) | `OrgService.isMember` / `roleOf` |
| Predicates | `canAccessOrg`, `canManageOrg`, plus subscription/jti-scoped wrappers | `isMember`, `hasRole(orgId, roleName)`, `isOwnerOrAdmin` |
| Typical call sites | resource-keyed endpoints (subscriptions, licenses, billing, webhooks, api-keys, SCIM) | pure org-keyed endpoints (`OrgController`), and `or`-composed in `audit`/`sso` controllers |

Rule of thumb: use **`@tenantAccess`** when the endpoint is keyed by a resource that *belongs* to an org (subscription id, license jti) or when you specifically need API-key tenancy semantics; use **`@orgAccess`** for endpoints that operate directly on the org and are exclusively human-driven. Both share the same role-rank ladder and the same super-admin bypass.

### SCIM note (contract divergence worth flagging)

`ScimUserController` gates every method with `hasAuthority('scim.manage') and @tenantAccess.canAccessOrg(@scimOrg.callerOrgId())`. SCIM requests carry no org in the path, so `@scimOrg` (`ScimOrgResolver.callerOrgId()`) derives it from the principal — returning the key's `apiKeyOrgId` for an API key and `null` for any non-key principal (which then fails the predicate, since `canAccessOrg(null)` is `false`). Two things to note:

1. The controller's own contract comment observes that the "bucket contract" originally named `canManageOrg(#orgId)`, but SCIM intentionally uses `canAccessOrg(...)` instead. That is **not** a downgrade in practice: `canManageOrg` would *deny all API keys* (tier 2), which would make SCIM provisioning — an inherently machine-driven, key-authenticated flow — impossible. The write authority is instead supplied by the explicit `scim.manage` scope AND-composed in the SpEL, while `canAccessOrg` enforces that the key may only act within its own bound org. Same end result the tenant model intends: a key can SCIM-manage exactly its own org and no other.
2. A human super-admin cannot drive SCIM endpoints (no org-bound key → `callerOrgId()` is `null` → denied), by design.
