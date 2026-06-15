# `com.example.cp.scim` — SCIM 2.0 User Provisioning

## Module overview

This package implements a **SCIM 2.0** (System for Cross-domain Identity Management, RFC 7643 / RFC 7644) `User` provisioning surface so that an enterprise customer's Identity Provider (IdP) — Okta, Entra ID/Azure AD, OneLogin, etc. — can automatically **provision, update, and deprovision** users in the control panel without a human admin clicking through the UI. The IdP authenticates with an **org-bound API key** carrying the `scim.manage` scope and drives a small REST surface under `/scim/v2/Users` (list, get, create, replace, patch, delete). Internally the package keeps a per-org **bridge table** (`scim_user_mappings`) that links an IdP's user namespace (`externalId`) to the control panel's own `users` rows, while delegating the actual user lifecycle (create/deactivate, password policy, session revocation, audit) to the platform's `UserService` so SCIM never becomes a back door around those invariants.

The package is small (11 Java files) but dense with SCIM-conformance and multi-tenant-isolation decisions. The two files that carry almost all the logic are `ScimUserController` (HTTP, filter parsing, PATCH-body parsing, SCIM error encoding) and `ScimService` (business logic, uniqueness, soft-vs-hard delete lifecycle). The rest are wire-format records (`ScimUser`, `ScimName`, `ScimEmail`, `ScimListResponse`, `ScimError`), the JPA entity + repository (`ScimUserMapping`, `ScimUserMappingRepository`), the org-resolution helper (`ScimOrgResolver`), and a custom `Pageable` (`OffsetPageRequest`) that exists purely to honor SCIM's 1-based absolute `startIndex` pagination correctly.

### How it fits the bigger picture

```
Enterprise IdP (Okta / Entra)
   │  HTTP + API key (scope: scim.manage), media type application/scim+json
   ▼
/scim/v2/Users  ──►  ScimUserController ──► ScimService
                          │ (parse filter,        │ (org-scoped lifecycle,
                          │  PATCH/PUT body,       │  uniqueness, audit)
                          │  SCIM error encoding)  │
                          │                        ├─► UserService  (create/deactivate user:
                          │                        │      password policy, session revocation,
                          │                        │      audit of user.* events)  [bucket A]
                          │                        ├─► OrgMemberRepository (ensure membership)
                          │                        ├─► ScimUserMappingRepository (the bridge table)
                          │                        └─► AuditWriter (fail-closed scim.user.* rows)
ScimOrgResolver ◄─────────┘ (derive org from the API key, no orgId in the path)
```

SCIM is a *consumer* of the user/org/audit subsystems; it owns only the `scim_user_mappings` table and the `scim.manage` permission seed (migration `16-scim.sql`). It deliberately routes user mutations through `UserService` rather than touching `users` directly, with two pragmatic exceptions documented below (display-name set and SUSPENDED→ACTIVE reactivation), so that the heavyweight invariants — password hashing, `token_version` session revocation, the `user.*` audit trail — keep firing. The SCIM resource id exposed to the IdP is **never** the raw `users.id`; it is the per-org mapping id, which is the linchpin of cross-tenant isolation: a tenant can only address rows in its own org, and cross-org ids simply 404.

---

## Quick file index

| File | Kind | Responsibility |
|---|---|---|
| `ScimUserController.java` | `@RestController` | HTTP surface, AuthN/Z gate, filter parsing, PATCH/PUT body parsing, SCIM error encoding |
| `ScimService.java` | `@Service` | Org-scoped provisioning/deprovisioning business logic, uniqueness, lifecycle, audit |
| `ScimUserMapping.java` | `@Entity` | The durable per-org bridge row (externalId ↔ user) |
| `ScimUserMappingRepository.java` | Spring Data repo | Org-scoped finders for the bridge table |
| `ScimOrgResolver.java` | `@Component("scimOrg")` | Resolves the operative org from the calling API-key principal |
| `OffsetPageRequest.java` | `Pageable` impl | Absolute-offset pagination so SCIM `startIndex` is honored exactly |
| `ScimUser.java` | `record` | SCIM core `User` resource (request + response), incl. nested `ScimMeta` |
| `ScimName.java` | `record` | SCIM `name` complex attribute |
| `ScimEmail.java` | `record` | SCIM multi-valued `emails` entry |
| `ScimListResponse.java` | `record` | SCIM `ListResponse` envelope for `GET /Users` |
| `ScimError.java` | `record` | SCIM `Error` response shape (instead of RFC-7807 ProblemDetail) |

---

## Data model

### `ScimUserMapping.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimUserMapping.java`

**Responsibility.** The durable, per-org bridge between an IdP's user namespace and a control-panel `users` row. It maps 1:1 to the `scim_user_mappings` table created in migration `16-scim.sql`. Because `spring.jpa.hibernate.ddl-auto=validate`, every column here MUST match the DDL exactly or the app fails to boot.

**Class:** `@Entity @Table("scim_user_mappings")`, Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`.

| Field | Column | Notes |
|---|---|---|
| `UUID id` | `id` (PK) | **This is the SCIM resource id exposed to the IdP** — never `users.id`. |
| `UUID orgId` | `org_id` (NOT NULL, FK `organizations` `ON DELETE CASCADE`) | Pins the mapping to the calling API key's org. |
| `String externalId` | `external_id` (nullable) | The IdP-assigned key; unique per org via `ux_scim_user_mappings_org_external`. |
| `UUID userId` | `user_id` (NOT NULL, FK `users` `ON DELETE CASCADE`) | The linked control-panel user. |
| `OffsetDateTime createdAt` | `created_at` (NOT NULL) | Set in code (`OffsetDateTime.now()`); also `DEFAULT now()` in DDL. Used as the stable list sort key (`ASC`). |

**Why it exists / gotchas.**
- The whole point is *indirection*: by handing the IdP a per-org mapping id rather than a raw user id, a SCIM client can only ever name users it provisioned **in its own org**. Even if two orgs link the same underlying user (same email), each org sees a distinct resource id.
- `externalId` is nullable: an IdP may POST a user with no `externalId`. The uniqueness constraint `UNIQUE(org_id, external_id)` in Postgres treats multiple NULLs as distinct, so several externalId-less mappings can coexist in one org (uniqueness is then enforced on the user side via `(org_id, user_id)` checks in the service).
- No JPA `@Version` here (unlike `User`): mapping rows are created/deleted, and the only mutable column (`externalId`) is guarded by an explicit pre-check plus a DB-constraint catch in the service.

### `ScimUserMappingRepository.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimUserMappingRepository.java`

**Responsibility.** Spring Data JPA repository for `ScimUserMapping`. The design principle stated in its Javadoc is **every tenant-facing finder is org-scoped** — there is deliberately no bare `findById` used by the service for reads, because cross-tenant isolation is enforced by *always pairing the id/externalId with the caller's `orgId`*.

| Method | Purpose / where used |
|---|---|
| `Optional<ScimUserMapping> findByIdAndOrgId(id, orgId)` | The canonical "fetch this resource, but only if it's mine" read. Used by `get`, `apply` (PUT/PATCH), `deprovision`. A cross-org id returns empty → controller renders 404. |
| `Optional<ScimUserMapping> findByOrgIdAndExternalId(orgId, externalId)` | Uniqueness pre-check on provision + externalId change; also `externalId eq` filter resolution. |
| `Optional<ScimUserMapping> findByOrgIdAndUserId(orgId, userId)` | `userName eq` filter resolution (email→user→mapping); membership/duplicate checks. |
| `boolean existsByOrgIdAndUserId(orgId, userId)` | Provision-time "is this user already mapped in the org?" duplicate guard. |
| `Page<ScimUserMapping> findByOrgId(orgId, Pageable)` | Paged list (used with `OffsetPageRequest`). |
| `List<ScimUserMapping> findByOrgId(orgId)` | Unpaged variant (declared; not used by the service's current paths). |
| `long countByOrgId(orgId)` | `totalResults` for the `ListResponse` envelope (full count, independent of the page window). |

**Gotcha.** `JpaRepository` still inherits a bare `findById(UUID)` and `delete(...)`. The service uses `findByIdAndOrgId` for all tenant reads, but does call the inherited `delete(mapping)` in `deprovision` — that's safe because the `mapping` was already org-checked when fetched.

---

## Wire-format records (request/response DTOs)

These are immutable Java `record`s serialized by Jackson. Most carry `@JsonInclude(NON_NULL)` so unset optional attributes stay off the wire, which is valid SCIM (attributes are returned only when present).

### `ScimUser.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimUser.java`

**Responsibility.** The SCIM 2.0 core `User` resource (`urn:ietf:params:scim:schemas:core:2.0:User`, RFC 7643 §4.1). Used **both** as the request body the IdP POSTs/PUTs and as the response the control panel returns.

**Record components.**

| Component | Meaning in this codebase |
|---|---|
| `List<String> schemas` | Always `["urn:ietf:params:scim:schemas:core:2.0:User"]` on responses. |
| `String id` | The `ScimUserMapping` id (per-org resource id), **not** the raw user id. |
| `String externalId` | The IdP-assigned key (echoes `mapping.externalId`). |
| `String userName` | Maps to the control-panel **email** (the unique login). |
| `ScimName name` | Complex name; on responses built only when `fullName != null`. |
| `String displayName` | Mirrors `users.full_name`. |
| `List<ScimEmail> emails` | Exactly one `primaryWork` entry built from the user's email. |
| `Boolean active` | `true` ⇔ `users.status == ACTIVE`; `false` once suspended/deprovisioned. |
| `ScimMeta meta` | Carries `resourceType` + `location` for client bookkeeping. |

**Constants & nested types.**
- `public static final String SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User"`.
- Nested `record ScimMeta(String resourceType, String location)` with factory `forUser(String id)` → `new ScimMeta("User", "/scim/v2/Users/" + id)`. So `meta.location` always points at the resource's own per-org URL.

**Collaborators.** Produced by `ScimService.buildScimUser`; consumed as `@RequestBody` by `createUser`/`replaceUser`. (PATCH does **not** bind to `ScimUser` — see below.)

**Gotcha.** Because it doubles as request and response, the IdP can technically send `id`/`schemas`/`meta` on create; the service ignores them and assigns its own. On create, the meaningful inputs are `userName`/`emails`, `externalId`, `displayName`/`name.formatted`, and `active`.

### `ScimName.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimName.java`

SCIM `name` complex attribute (RFC 7643 §4.1.1). Components: `formatted`, `givenName`, `familyName`. Only `formatted` is meaningfully used: it maps to/from `users.full_name`. On responses the service builds `new ScimName(fullName, null, null)` — `givenName`/`familyName` are accepted on input (best-effort split an IdP may send) but not stored or returned. `@JsonInclude(NON_NULL)`.

### `ScimEmail.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimEmail.java`

SCIM multi-valued `emails` entry (RFC 7643 §4.1.2). Components: `value`, `type`, `primary`. Factory `primaryWork(value)` → `new ScimEmail(value, "work", Boolean.TRUE)`. Because the control panel keys users by a **single** email, responses always emit exactly one entry (`primary=true`, `type="work"`). On input, the service's `extractEmail` prefers the `primary` email, else the first non-blank `value`, but only when `userName` is absent. `@JsonInclude(NON_NULL)`.

### `ScimListResponse.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimListResponse.java`

SCIM `ListResponse` envelope (RFC 7644 §3.4.2) returned by `GET /scim/v2/Users`. Components: `schemas`, `totalResults`, `startIndex`, `itemsPerPage`, **`Resources`** (capitalized to match the SCIM JSON key exactly — Jackson serializes the field name verbatim). Constant `SCHEMA_LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse"`. Factory `of(resources, totalResults, startIndex, itemsPerPage)` stamps the schema list.

**Semantics worth internalizing:**
- `totalResults` = **full** count matching the filter, not the page size (comes from `countByOrgId` or the 0/1 filter result).
- `startIndex` / `itemsPerPage` are **1-based** per SCIM. `itemsPerPage` is the actual returned page size, which can be smaller than the requested `count` when DELETED users are skipped (see lifecycle below).

### `ScimError.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimError.java`

SCIM `Error` response (RFC 7644 §3.12). Components: `schemas`, `status` (HTTP status **as a string**, per schema), `scimType` (optional keyword like `uniqueness`, `invalidValue`, `invalidFilter`), `detail` (human-readable, caller-safe). Constant `SCHEMA_ERROR = "urn:ietf:params:scim:api:messages:2.0:Error"`. Factory `of(int httpStatus, String scimType, String detail)`.

**Why it exists.** SCIM clients expect this shape, not the platform's RFC-7807 `ProblemDetail`. So SCIM endpoints serialize errors with this record **directly** (and set the matching HTTP status) rather than throwing into `GlobalExceptionHandler`, which would render a non-SCIM body and confuse the IdP's error handling. This is the reason `ScimService` throws private exceptions that the controller catches and converts, instead of letting them bubble.

---

## Org resolution & pagination helpers

### `ScimOrgResolver.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimOrgResolver.java`

**Responsibility.** Resolves *which org* a SCIM request operates on. SCIM endpoints carry **no orgId in the path** (the IdP doesn't know the control panel's internal org ids), so the org is derived from the authenticated principal.

**Bean name.** `@Component("scimOrg")` — exposed under that name specifically so it can be referenced from method-security SpEL (`@scimOrg.callerOrgId()`), AND used inside the controller methods so the controller scopes against the *exact same value* the security gate authorized.

**Method.** `UUID callerOrgId()`:
```java
AuthenticatedUser u = SecurityUtils.currentUser().orElse(null);
if (u == null) return null;
if (u.isApiKey()) return u.apiKeyOrgId();
return null;   // any non-api-key principal (e.g. a human super-admin) → null
```

**Why `null` for humans.** A human super-admin has no path/param org to scope to, so they cannot drive these endpoints without an org-bound key. Returning `null` makes the `@PreAuthorize` gate (`@tenantAccess.canAccessOrg(null)`) deny. SCIM is intentionally **API-key-only**.

**Gotcha.** `callerOrgId()` is called twice per request — once by the SpEL gate, once inside the controller body. Both must agree; since both read the same `SecurityContext`, they do. If you ever add caching or a request-scoped override, keep them consistent or you reopen a tenant-isolation hole.

### `OffsetPageRequest.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/OffsetPageRequest.java`

**Responsibility.** A `Pageable` keyed by an **absolute row offset** rather than a page number, extending Spring Data's `AbstractPageRequest`.

**Why it exists (the bug it fixes).** SCIM's `startIndex` (RFC 7644 §3.4.2.4) is a **1-based absolute offset**, not a page number. Spring Data's stock `PageRequest` only supports offsets of `pageNumber * pageSize`. If you compute `pageNumber = (startIndex-1)/count`, an *unaligned* `startIndex` (e.g. `startIndex=3, count=2`) gets rounded to a page boundary and you return a **shifted/overlapping window** — a real conformance bug flagged as **P3** and fixed by this class. `OffsetPageRequest` instead reports the offset literally.

**Construction & key methods.**
- `OffsetPageRequest(long offset, int limit, Sort sort)` → calls `super(0, limit < 1 ? 1 : limit)` (page number fixed at 0; page size clamped to ≥1 because `AbstractPageRequest` rejects 0), clamps `offset` to ≥0, and defaults a null `sort` to `Sort.unsorted()`.
- `getOffset()` returns the literal `offset` (this is the override that makes the whole thing work).
- `next()/previous()/first()/withPage(n)` produce offset-shifted instances; `previous()` and the constructor floor at 0.
- `equals`/`hashCode` over `(offset, pageSize, sort)`.

**How it's used.** `ScimService.list` computes `offset = safeStart - 1` (convert 1-based → 0-based) and builds `new OffsetPageRequest(offset, max(count,1), Sort.by(ASC, "createdAt"))`, then calls `mappingRepository.findByOrgId(orgId, pageable)`. The `ASC, createdAt` sort gives a **stable** ordering so pagination windows don't reshuffle between calls.

**Gotcha.** Because page size is clamped to ≥1, a requested `count=0` would yield a 1-row query if you reached this path — but the service short-circuits `count == 0` *before* building the `OffsetPageRequest` (returns an empty `Resources` with the correct `totalResults`), so the clamp never produces a wrong-sized page in practice.

---

## The HTTP surface

### `ScimUserController.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimUserController.java`

**Responsibility.** The `@RestController` under `@RequestMapping("/scim/v2/Users")`. It owns: the authn/authz gate, content negotiation for the SCIM media type, filter-string parsing, PATCH-body parsing (the lenient/strict matrix), SCIM error encoding, and client-IP resolution for audit. It holds **no business logic** — every mutation delegates to `ScimService`.

**Collaborators (constructor-injected):** `ScimService scimService`, `ScimOrgResolver scimOrg`, `TrustedProxyResolver proxyResolver`.

#### Authn / Authz

A single shared SpEL gate string is applied to every endpoint via `@PreAuthorize(GATE)`:

```java
private static final String GATE =
    "hasAuthority('scim.manage') and @tenantAccess.canAccessOrg(@scimOrg.callerOrgId())";
```

- `hasAuthority('scim.manage')` — the SCIM client's API key must carry the `scim.manage` scope. `ApiKeyAuthFilter` turns key scopes into Spring authorities and binds `apiKeyOrgId` onto the principal.
- `@tenantAccess.canAccessOrg(@scimOrg.callerOrgId())` — the resolved org must be one the principal can access; for an API-key principal this enforces exactly that the key is **bound to** the resolved org (`orgId.equals(apiKeyOrgId)`).

**Important contract note (documented in the class Javadoc):** the bucket contract *named* `@tenantAccess.canManageOrg(#orgId)`, but that checker (owned by another bucket, not editable here) returns `false` for **all** API-key principals by design — which would make every SCIM endpoint a hard 403, defeating the API-key-only design. The functionally-equivalent gate used is `canAccessOrg(...)`, which preserves the contract's intent (key bound to org) while still allowing the request to run. The `scim.manage` scope is itself the management-grade authority gating the mutations. If you "fix" the gate back to `canManageOrg`, you break SCIM entirely.

#### Media types

`SCIM_JSON = "application/scim+json"` (RFC 7644 §3.1) is listed first in both `consumes` and `produces`, with `application/json` also accepted/produced for lenient clients. All success and error responses set `Content-Type: application/scim+json` via `scimOk`/`scimError`.

#### Endpoints

| Verb + path | Method | Flow |
|---|---|---|
| `GET /scim/v2/Users` | `listUsers(filter, startIndex, count)` | Parse optional `eq` filter; defaults `startIndex=1`, `count=100`; delegate to `scimService.list`. |
| `GET /scim/v2/Users/{id}` | `getUser(id)` | Parse id → UUID; `scimService.get`; 404 on bad id or miss. |
| `POST /scim/v2/Users` | `createUser(ScimUser)` | `scimService.provision`; **201** on success; catch `ScimConflictException`→409 `uniqueness`, `ScimBadRequestException`→400. |
| `PUT /scim/v2/Users/{id}` | `replaceUser(id, ScimUser)` | Full replace; compute `displayName` from `displayName` else `name.formatted`; `scimService.replace`; catch conflict→409. |
| `PATCH /scim/v2/Users/{id}` | `patchUser(id, Map)` | Partial update; parse flat or PatchOp body; `scimService.update`; catch conflict→409. |
| `DELETE /scim/v2/Users/{id}` | `deleteUser(id)` | `scimService.deprovision`; **204** on success, 404 if not in org. |

**Notes per endpoint:**

- **`listUsers`** — The filter is matched against `EQ_FILTER = ^\s*(\w+)\s+eq\s+"([^"]*)"\s*$` (case-insensitive). This deliberately supports **only** `<attr> eq "<value>"` — the single operator SCIM mandates for basic interop. Anything else (a `co`/`sw` operator, `and`/`or`, a missing-quote value) fails to match and returns **400 `invalidFilter`** "Only 'eq' filters on userName/externalId are supported". A matched filter becomes `new ScimService.ScimFilter(attr, value)`. Defaults: `startIndex=1`, `count=100`.

- **`getUser` / all `{id}` endpoints** — `parseId` does `UUID.fromString` inside a try/catch; a non-UUID id returns `null` → the controller renders **404** (`notFound`) rather than a 400. This is intentional: a garbage id is "no such resource" from the IdP's perspective.

- **`createUser`** — Returns `201` with `Content-Type: application/scim+json`. Two service exceptions are translated to SCIM errors here (not in a global handler) so the body stays SCIM-shaped.

- **`replaceUser` (PUT = full replace, RFC 7644 §3.5.1)** — The controller pre-computes `displayName` as `request.displayName()` else `request.name().formatted()` else `null`, then passes `request.active()`, that displayName, and `request.externalId()` to `scimService.replace`. The comment is explicit: **attributes absent from the body are reset to defaults**, not preserved — `active`→true, `displayName`/`externalId`→cleared.

- **`patchUser` (PATCH)** — Note the body binds to `Map<String,Object>`, **not** `ScimUser`. This is because SCIM `PatchOp` (`{"Operations":[...]}`) is a different schema than the User resource, and the controller wants to accept both the strict PatchOp form and a lenient flat form. Parsing is done by `parsePatch` (below). Only `null`-meaning-"unchanged" attributes are forwarded, giving true partial-update semantics.

- **`deleteUser`** — `204 No Content` on success. If `deprovision` returns `false` (mapping not in the caller's org), renders **404**.

#### PATCH body parsing — `parsePatch` and `PatchAttrs`

`private record PatchAttrs(Boolean active, String displayName, String externalId)` is the normalized output. `parsePatch(Map<String,Object> body)` extracts the three supported mutable attributes from **either** form:

1. **Strict SCIM PatchOp** — `body.get("Operations")` (falls back to lowercase `"operations"` for lenient clients), expected to be a list of op maps. For each op:
   - `op == "remove"` with `path == "active"` → treated as **deactivation** (`active = false`). This is how some IdPs deprovision.
   - op **with a `path`** → match `path` case-insensitively against `active` / `displayName` / `externalId` and read `value`.
   - op **with no `path`** but a `value` that is a sub-object (`Map`) → pull `active` / `displayName` / `externalId` out of that map (the "value is a bag of attributes" form).
   - Any other op/path is **silently ignored** (forward-compatible; an unsupported attribute won't error the whole PATCH).
2. **Lenient flat form** — if there's no `Operations` array, read top-level `active` / `displayName` / `externalId`; if `displayName` is absent but a nested `name.formatted` exists, use that.

Helpers: `asString(o)` → `o.toString()` or null; `asBoolean(o)` → handles real `Boolean`, parses a `String`, else null (so a missing value is "unchanged", not "false").

**Gotcha.** Because unsupported ops are ignored rather than rejected, a PATCH that tries to change, say, `userName` (email) will return **200 with the resource unchanged** rather than an error — by design, email is immutable via SCIM update (changing it is a re-provision). Don't expect a 4xx for unsupported attribute mutations through PATCH.

#### Response / error / context helpers

- `scimOk(body)` → `200` + SCIM content type.
- `scimError(status, scimType, detail)` → status + SCIM content type + `ScimError.of(...)` body.
- `notFound(id)` → `scimError(404, null, "User " + id + " not found")`.
- `parseId(id)` → UUID or null (described above).
- `actorUserId()` → `SecurityUtils.currentUser().map(u -> u.userId()).orElse(null)` — for an API-key principal this is whatever user the key represents (may be null); recorded as the audit actor.
- `clientIp()` → resolves the real client IP via `proxyResolver.resolveClientIp(req)` (respects trusted-proxy `X-Forwarded-For` handling); swallows exceptions to `null` and logs at debug, so audit IP resolution never breaks the request.
- `currentRequest()` → pulls the `HttpServletRequest` from `RequestContextHolder`; null-safe.

**Gotcha.** `import com.example.cp.common.AuditContext;` is present but `AuditContext` is not referenced in the controller — the audit suppression (`markRecorded`) happens in the service. Harmless unused import.

---

## The business logic

### `ScimService.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/scim/ScimService.java`

**Responsibility.** All SCIM business logic, always scoped to a single org (the caller's API-key org, passed in by the controller). It owns the mapping between an IdP's namespace and the `users`/`org_members` tables, and **delegates user lifecycle to `UserService`** so SCIM never bypasses password policy, session revocation, or the `user.*` audit semantics enforced there.

**Collaborators (constructor-injected):** `UserService` (create/deactivate user), `UserRepository` (read user, name set, reactivation), `OrgMemberRepository` (ensure membership), `ScimUserMappingRepository` (the bridge), `AuditWriter` (fail-closed `scim.user.*` rows). Plus a static `SecureRandom RNG` for password generation.

**Tenant-isolation invariant (restated in code).** Every public method takes the resolved `orgId` and only ever touches `ScimUserMapping` rows for that org; the SCIM resource id is the per-org mapping id, so an IdP can never address — or even discover the existence of — another tenant's users.

#### Inner types

- `public record ScimFilter(String attribute, String value)` — a parsed `eq` filter, produced by the controller.
- `public static class ScimBadRequestException extends RuntimeException` — carries a `scimType()` (default `invalidValue`); controller → 400.
- `public static class ScimConflictException extends RuntimeException` — controller → 409 `uniqueness`.

#### `list(orgId, ScimFilter filter, int startIndex, int count)` — `@Transactional(readOnly=true)`

Two distinct paths:

1. **Filtered (`eq` on userName/externalId)** — A single-valued `eq` resolves to **at most one** mapping (`resolveFilter`). The single result (or none) is converted via `toScimUser`, then the 1-based window is applied to that 0-or-1-element list so pagination stays well-defined: if `safeStart > size` or `count == 0`, return an empty page but still report the real `totalResults` (0 or 1). This honors a client that filters *and* paginates.

2. **Unfiltered** — `total = countByOrgId(orgId)` for `totalResults`. If `count == 0`, short-circuit with an empty `Resources`. Otherwise compute `offset = safeStart - 1`, build an `OffsetPageRequest(offset, max(count,1), Sort.ASC createdAt)`, fetch the page, and convert each mapping with `toScimUser`, **skipping** any whose linked user is `DELETED` (GDPR-erased). `itemsPerPage` is the actual rendered count.

Input hardening: `safeStart = max(startIndex,1)`, `safeCount = max(count,0)`.

**Gotcha (leaky page size).** Because DELETED users are filtered out *after* the DB page is fetched, a page can return **fewer** items than `count` even when more non-deleted rows exist further down. A strict client paginating by `itemsPerPage` could under-fetch. In practice DELETED users are rare and the mapping should also be gone, but it's a known sharp edge.

#### `get(orgId, resourceId)` — `@Transactional(readOnly=true)`

`findByIdAndOrgId(resourceId, orgId).flatMap(m -> toScimUser(orgId, m))`. Empty when not in the org **or** when the linked user is DELETED (→ controller 404).

#### `provision(orgId, actorUserId, ip, ScimUser request)` — `@Transactional`

The create/link path. Flow:

1. **Email** — `email = normalizeEmail(extractEmail(request))` (prefer `userName`, else primary/first `emails` value, trimmed). Blank → `ScimBadRequestException("invalidValue", "userName (or a primary email) is required")`.
2. **externalId** — `trimToNull(request.externalId())`.
3. **externalId uniqueness pre-check** — if `externalId != null` and a mapping with that `(orgId, externalId)` already exists → `ScimConflictException("A user with that externalId already exists")`.
4. **Find-or-create user** by email (`userRepository.findByEmail`):
   - **Not found** → `userService.createUser(email, displayNameOf(request), randomPassword())`. `createUser` enforces email uniqueness, runs the password policy, hashes the password, and sets `AuditContext("user.created")`. `newUser = true`.
   - **Found** →
     - if already mapped in this org (`existsByOrgIdAndUserId`) → `ScimConflictException("A user with that userName already exists")` (double-provision).
     - if the user is `SUSPENDED` (a previously deprovisioned user) → flip back to `ACTIVE` and save (re-provisioning revives the account). *This is one of the two deliberate direct-`users`-writes; it does not route through `UserService` because it's a simple status flip with no session/policy implication on activation.*
5. **Ensure org membership** — idempotent: if no `OrgMember(orgId,userId)` exists, save one with `Role.MEMBER` and `addedAt = now`.
6. **Honor `active=false` on create** — `active = request.active() == null || request.active()`; if the IdP created the user inactive, immediately `userService.deactivate(user.id)` after linking (so session revocation + SUSPENDED status fire).
7. **Create the mapping** — `ScimUserMapping(id=Ids.newId(), orgId, externalId, userId, createdAt=now)`, persisted via `saveMappingHandlingUniqueness` (DB-constraint → 409, closing the check-then-insert race).
8. **Audit** — fail-closed `scim.user.provisioned` row with `{user_id, email, external_id, new_user}` plus the base `{org_id, scim_mapping_id}`.
9. **Suppress the interceptor's duplicate** — `AuditContext.markRecorded()` so the mutating-endpoint interceptor doesn't *also* emit `createUser`'s less-specific `user.created` row. The fail-closed write above is the canonical record.
10. **Return** — `buildScimUser(saved, <freshly reloaded user>)`.

**Why a random password.** SCIM-provisioned users authenticate via the IdP (SSO), never with a password. `randomPassword()` produces a 32-char value that satisfies `PasswordPolicy` (≥12 chars, mixed case, digit, symbol) but is **never disclosed** — it exists only so `createUser` can run its normal path. The generator guarantees at least one upper/lower/digit/symbol then fills 28 more from the full alphabet (ambiguous chars like `O/0/I/1/l` excluded). Note: the guaranteed chars are placed at fixed leading positions rather than shuffled — fine for an undisclosed secret, but don't copy this generator for user-visible passwords expecting positional randomness.

#### `update(...)` / `replace(...)` — `@Transactional`

Both delegate to the shared engine `apply(...)`:
- `update(...)` → `apply(..., fullReplace=false)` (PATCH semantics: `null` arg = leave unchanged).
- `replace(...)` → first coerces `effectiveActive = active != null ? active : TRUE` (an absent `active` on a present resource defaults to active per SCIM), then `apply(..., fullReplace=true)` (PUT semantics: `null` displayName/externalId **clears**).

#### `apply(orgId, actor, ip, resourceId, active, displayName, externalId, fullReplace)` — private engine

1. **Resolve** — `findByIdAndOrgId`; null → `Optional.empty()` (→ 404).
2. **Load user** — null or `DELETED` → `Optional.empty()` (a GDPR-erased user has no addressable SCIM resource).
3. **Display name** —
   - `fullReplace`: set `user.fullName = displayName` (even to null) if it differs.
   - PATCH: set only if `displayName != null && differs`.
   - *Second deliberate direct-`users` write:* a name-only change is a plain setter + `userRepository.save`, not `UserService.updateProfile`, because it touches no password/policy/session state.
4. **externalId** —
   - `fullReplace && externalId == null`: clear `mapping.externalId` if set.
   - else if `externalId != null`: `trimToNull`; if it differs, do a **pre-check** against `(orgId, normalized)` excluding self → `ScimConflictException` for a friendly message; then `saveAndFlush`, catching `DataIntegrityViolationException` → `ScimConflictException` (closing the concurrent race the pre-check can't).
5. **active** — if supplied and it differs from current:
   - `true` and currently not ACTIVE → flip to `ACTIVE`, save, audit `scim.user.reactivated`.
   - `false` and currently ACTIVE → `userService.deactivate(user.id)` (→ SUSPENDED + `token_version` bump = session revocation), audit `scim.user.deprovisioned`.
6. **Audit suppression** — if anything `changed`, `AuditContext.markRecorded()`.
7. **Return** — `buildScimUser(mapping, <reloaded user>)`.

**Gotcha.** Reactivation via `apply` uses a direct status flip (not a `UserService` call), so it does **not** bump `token_version`. That's fine for reactivation (you *want* the user usable again), but be aware the activation and deactivation paths are asymmetric on purpose. The reload at the end ensures the returned `active` reflects the post-`deactivate` state even though `deactivate` mutated a different managed instance.

#### `deprovision(orgId, actor, ip, resourceId)` — `@Transactional`

The DELETE path, and the most subtle lifecycle decision in the package:

1. `findByIdAndOrgId`; null → return `false` (→ 404).
2. `userService.deactivate(mapping.userId)` — user → **SUSPENDED** + session revocation. The user is **soft-deleted at most** (SUSPENDED), never hard-deleted here.
3. Capture `userId` + `externalId` **before** deleting the row.
4. Audit fail-closed `scim.user.deprovisioned` with `{user_id, external_id?}`.
5. `mappingRepository.delete(mapping)` — **hard-delete the mapping row**.
6. `AuditContext.markRecorded()`, return `true`.

**The soft-vs-hard split (read this carefully).**
- The **user** is *soft*-retained as SUSPENDED (durable deprovisioned state; preserves the row for billing/audit/GDPR and other orgs that may share the user).
- The **mapping** is *hard*-deleted. Why: SCIM DELETE must be conformant — a subsequent `GET /Users/{id}` returns **404**, and re-provisioning the same `userName`/`externalId` must **succeed** (re-linking the now-revivable user, which `provision` flips back to ACTIVE) instead of hitting a permanent 409. Keeping a soft "deleted" mapping would make re-provision a forever-conflict.
- The externalId correlation is preserved in the `scim.user.*` audit payloads, so deleting the mapping row doesn't lose the audit trail.

#### Internal helpers

| Helper | Role |
|---|---|
| `saveMappingHandlingUniqueness(mapping)` | `saveAndFlush`; translate `DataIntegrityViolationException` (either `(org_id,external_id)` or `(org_id,user_id)` collision) → `ScimConflictException`. The race-closing backstop behind the in-memory `existsBy*/findBy*` guards. |
| `resolveFilter(orgId, filter)` | `externalid` → `findByOrgIdAndExternalId`; `username` → `findByEmail(normalize)` then `findByOrgIdAndUserId`. Unknown attr → empty. |
| `toScimUser(orgId, mapping)` | Load user, **filter out `DELETED`**, build `ScimUser`. SUSPENDED users still render (with `active=false`). |
| `buildScimUser(mapping, user)` | Assemble the response `ScimUser`: schema, mapping id, externalId, email as `userName`, name from fullName, single `primaryWork` email, `active = (status==ACTIVE)`, `meta.forUser`. |
| `audit(actor, org, action, mapping, ip, filler)` | Base payload `{org_id, scim_mapping_id}` + filler; `auditWriter.record(..., AuditOutcome.SUCCESS, /*failClosed*/ true)`. Identity-lifecycle events must commit a durable trail atomically with the change. |
| `extractEmail(request)` | `userName` else primary email else first non-blank email value. |
| `displayNameOf(request)` | `displayName` else `name.formatted` (blank-safe). |
| `normalizeEmail` / `trimToNull` | trim; null/empty → null. |
| `randomPassword()` | Policy-satisfying, undisclosed 32-char `SecureRandom` password (see provision notes). |

---

## End-to-end flows (cheat sheet)

**Provision a brand-new user (POST):**
```
IdP POST /Users {userName, externalId, displayName, active?}
 → gate: scim.manage + key bound to org
 → ScimService.provision:
     email required? → createUser(random pw) [user.created suppressed]
     ensure OrgMember(MEMBER)
     active=false? → deactivate
     insert mapping (DB uniqueness backstop → 409)
     audit scim.user.provisioned (fail-closed) + markRecorded
 → 201 application/scim+json {id = mapping id, ...}
```

**Deprovision (DELETE):**
```
IdP DELETE /Users/{mappingId}
 → findByIdAndOrgId or 404
 → UserService.deactivate (SUSPENDED + token_version bump)
 → audit scim.user.deprovisioned
 → DELETE mapping row (hard)  → 204
   (user soft-retained; re-provision later revives it)
```

**PATCH active=false (typical IdP deactivation):**
```
{"Operations":[{"op":"replace","path":"active","value":false}]}
 → parsePatch → active=false
 → apply: UserService.deactivate, audit scim.user.deprovisioned → 200 (active:false)
```

---

## Gotchas a new engineer must know

1. **Resource id ≠ user id.** Everything the IdP sees is the `scim_user_mappings.id`. Never leak `users.id` into a SCIM body or you create a cross-tenant correlation/enumeration vector.
2. **The gate is `canAccessOrg`, not `canManageOrg`, on purpose.** Reverting it to the contract-named `canManageOrg` 403s every SCIM call because that checker rejects all API keys. The `scim.manage` scope is the real management authority.
3. **`ddl-auto=validate`.** Any change to `ScimUserMapping` columns must be mirrored in `16-scim.sql` or boot fails.
4. **Filters are `eq`-only.** Only `userName eq "..."` / `externalId eq "..."`; anything else → 400 `invalidFilter`. No `and`/`or`/`co`/`sw`.
5. **PATCH binds to a `Map`, PUT/POST bind to `ScimUser`.** PATCH must accept both PatchOp and flat shapes; unsupported PATCH ops are silently ignored (no error).
6. **PUT clears omitted attributes; PATCH leaves them.** This is the whole reason `apply(..., fullReplace)` exists — getting it backwards corrupts data on every IdP full-sync.
7. **DELETE = soft user (SUSPENDED) + hard mapping.** Re-provision revives the user; a soft-kept mapping would make re-provision a permanent 409.
8. **DELETED users vanish from SCIM** (404 on get, skipped in list) — but that post-fetch skip can make a list page return fewer than `count` items.
9. **Audit is fail-closed and de-duplicated.** `scim.user.*` rows must commit with the change; `AuditContext.markRecorded()` suppresses the interceptor's generic duplicate. Don't remove the `markRecorded()` calls or you'll get double/less-specific audit rows.
10. **SCIM errors are `ScimError`, not ProblemDetail.** Throw the service's `ScimBadRequestException`/`ScimConflictException` and let the controller's catch blocks render SCIM-shaped bodies; don't let SCIM paths fall through to `GlobalExceptionHandler`.
