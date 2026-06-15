# Package `com.example.cp.users` — User CRUD, profile & password management

## Module overview

This package is the **system-of-record for human user accounts** in the control-panel API. It owns the `users` table and exposes the full lifecycle of a user identity: creation, profile read/update, password change/reset, deactivation (suspend) and soft-delete. It is intentionally small (five files) but it is the *trust anchor* a surprising number of other subsystems route through, because it concentrates four cross-cutting concerns in one place:

1. **Password policy enforcement** — every place a password is set goes through `PasswordPolicy.validate(...)`, so weak/breached passwords cannot enter the system through a side door.
2. **Session revocation** — every status-changing or credential-changing operation bumps the per-user `token_version`, which is the bulk session-revocation primitive (it invalidates every JWT the user currently holds).
3. **Auditing** — every mutating operation sets an `AuditContext` action + target so the audit interceptor writes a correctly-attributed row.
4. **Optimistic locking** — the `User` entity carries a JPA `@Version`, so concurrent writers cannot silently clobber each other (lost-update protection).

The design philosophy is *"all user mutation must go through `UserService`"*. Sibling subsystems (SCIM, SSO JIT-provisioning, GDPR erasure, the auth/reset flow) are expected to call `UserService` rather than hand-rolling user creation/deactivation, precisely so they inherit the four guarantees above. Where a sibling *does* talk to `UserRepository` directly, it is a deliberate, commented exception (e.g. a name-only SCIM patch, or the erasure job that must null PII in ways the normal API forbids).

### Files at a glance

| File | Type | Responsibility |
|------|------|----------------|
| `User.java` | JPA `@Entity` | The persistent user row + `Status` enum + optimistic-lock & token-version columns. |
| `UserRepository.java` | Spring Data `JpaRepository` | CRUD + `findByEmail` / `existsByEmail` lookups (email is case-insensitive via CITEXT). |
| `UserDto.java` | `record` | Read-only API projection of a `User` that **excludes the password hash and token-version**. |
| `UserService.java` | `@Service` | All business logic: create, read, update-profile, change/set password, deactivate, soft-delete, touch-last-login, bulk session revocation. The transactional boundary. |
| `UserController.java` | `@RestController` | REST surface under `/api/v1/users`, with method-level `@PreAuthorize` and request DTOs. |

### How it fits the bigger picture

```
                  HTTP (/api/v1/users/**)
                          │
   admin-ui / API client  ▼
                  ┌──────────────────┐   @PreAuthorize (RBAC + "self" rule)
                  │  UserController  │   validates request DTOs (@Valid)
                  └────────┬─────────┘
                           │  (UUID id, plain fields)
                           ▼
   PasswordPolicy ◀──┐  ┌──────────────────┐  ──▶ AuditContext (action/target → audit_log)
   PasswordEncoder ◀─┤  │   UserService    │
   SessionRevocation ┤  │  (@Transactional)│  ──▶ SessionRevocationStore (Redis token-version)
        Store    ◀───┘  └────────┬─────────┘
                                 ▼
                          ┌──────────────┐        ┌───────────────────────────┐
                          │ UserRepository│ ◀──── │ AuthController (reset pwd) │
                          └──────┬───────┘        │ ScimService (provision)    │
                                 ▼                │ SsoProvisioningService(JIT)│
                          PostgreSQL `users`      │ ErasureService (GDPR)      │
                          (CITEXT email, CHECK     └───────────────────────────┘
                           status, version, token_version)
```

Downstream, the issued JWTs (whose `token_version` claim must match the user's current `token_version`) are what gate access to the *rest* of the control plane — including the licensing endpoints that mint the offline-verifiable Ed25519 `.lic` tokens consumed by customer Docker apps. So a `token_version` bump here (e.g. on password change or deactivation) immediately severs a user's ability to mint or revoke licenses, which is why session revocation lives inside this package's mutating operations rather than being bolted on by callers.

---

## `control-panel-api/src/main/java/com/example/cp/users/User.java`

### Responsibility
The JPA entity mapped to the PostgreSQL `users` table. It is the canonical, persistent representation of a human account. It deliberately holds *only* identity/credential/status state — no org-membership, no roles (those live in `org_members` and the RBAC tables), no MFA/SSO data (those hang off `user_mfa` / SSO tables keyed by `user_id`).

### Public types

#### `class User`
Lombok-generated: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. The no-args constructor exists for Hibernate; the builder is what `UserService.createUser` uses.

#### `enum User.Status { ACTIVE, SUSPENDED, DELETED }`
A nested enum persisted as a string (`@Enumerated(EnumType.STRING)`). Its three values map 1:1 to the DB `CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))` constraint defined in `01-organizations-users.sql`. There is **no hard-delete**: "delete" is modeled as the `DELETED` status (see `UserService.delete`). Keeping the enum and the CHECK constraint in lock-step is a gotcha — adding a status here without a matching migration will fail at the DB layer.

### Fields / columns

| Field | Column | Notes / WHY |
|-------|--------|-------------|
| `UUID id` | `id` (PK) | Assigned in code (not DB-generated in practice) via `Ids.newId()` → a **time-ordered UUIDv7** (`UuidCreator.getTimeOrderedEpoch()`). Time-ordering keeps B-tree index inserts append-friendly (vs. random UUIDv4 fragmentation). The DB also has `DEFAULT gen_random_uuid()` as a fallback for rows inserted outside the app. |
| `long version` | `version` | `@Version` optimistic-lock counter. Hibernate adds `WHERE version = ?` to every UPDATE and bumps it; a concurrent writer that read the old version gets an `OptimisticLockException`. Added by migration `17-optimistic-locking.sql`, **not** the original table DDL. |
| `String email` | `email` | `columnDefinition = "citext"` — the column is PostgreSQL **CITEXT**, so uniqueness and lookups are case-insensitive (`Alice@x.com` == `alice@x.com`). The `columnDefinition` keeps Hibernate schema-validation from complaining that `citext` ≠ `varchar`. `nullable = false`, and the DB enforces `UNIQUE`. |
| `String passwordHash` | `password_hash` | Nullable. A bcrypt hash (see `PasswordEncoder`). **Null is meaningful**: SSO/SCIM users may have a random throwaway password, and GDPR erasure *nulls* this. Code that checks passwords must treat null as "no password set" (see `changePassword`). Never exposed via `UserDto`. |
| `String fullName` | `full_name` | Nullable display name. The only field the self-service profile PATCH can change. |
| `Status status` | `status` | `@Enumerated(EnumType.STRING)`, not-null. Drives auth: `SUSPENDED`/`DELETED` users should not be able to authenticate (enforced in the auth layer, not here). |
| `boolean superAdmin` | `super_admin` | Platform-wide super-admin flag. When true, `AuthenticatedUser.hasAuthority(...)` short-circuits to `true` for *every* authority — this is the highest-privilege bit in the system. Note: `createUser` always sets this to `false`; there is no API path in this package to *grant* super-admin, which is by design (privilege escalation was a P0 audit finding). |
| `long tokenVersion` | `token_version` | `@Builder.Default = 0L`. The durable per-user session-revocation counter. Every JWT embeds the `token_version` at mint time; the auth filter rejects a token whose embedded version is below the user's current value. Bumping it = "log this user out everywhere." DB is the source of truth (migration `13-session-revocation.sql`); Redis is a write-through cache. |
| `OffsetDateTime createdAt` | `created_at` | Set in code at creation (`OffsetDateTime.now()`); DB also defaults to `now()`. |
| `OffsetDateTime lastLoginAt` | `last_login_at` | Nullable; updated by `touchLastLogin` after a successful login. |

### Gotchas
- **The entity has columns the original migration doesn't.** `version` and `token_version` are *later* migrations (`17-...` and `13-...` respectively). If you read only `01-organizations-users.sql` you'll wrongly think the schema lacks them. The full column set is the union of all migrations.
- `@Builder.Default` on `tokenVersion` is required — without it Lombok's builder would initialize the primitive to `0` but *ignore* the `= 0L` initializer, which is fine here (both are 0) but is a classic Lombok footgun to be aware of when changing the default.
- `@AllArgsConstructor` includes `version` and `tokenVersion`, so be careful constructing `User` positionally; prefer the builder.

---

## `control-panel-api/src/main/java/com/example/cp/users/UserRepository.java`

### Responsibility
Spring Data JPA repository for `User`. Inheriting `JpaRepository<User, UUID>` gives the full CRUD/paging surface (`save`, `findById`, `findAll`, `delete`, etc.) for free; this interface adds the two email-keyed lookups the rest of the app needs.

### Public interface

#### `interface UserRepository extends JpaRepository<User, UUID>`
Annotated `@Repository` (mostly documentary — Spring Data would proxy it regardless).

| Method | Returns | What / WHY |
|--------|---------|------------|
| `findByEmail(String email)` | `Optional<User>` | Derived query → `SELECT … WHERE email = ?`. Because `email` is **CITEXT**, the match is case-insensitive at the DB. Used by login (`AuthController`/auth layer), SCIM lookup-by-username, and `UserService.getByEmail`. |
| `existsByEmail(String email)` | `boolean` | Derived `EXISTS` query. Used by `UserService.createUser` for a friendly pre-check ("user already exists" → 409) **before** attempting the insert. |

### Collaborators
- **Called by:** `UserService` (all persistence), `AuthController` (login/reset), `ScimService` (provision/lookup/patch), `SsoProvisioningService` (JIT lookup), `ErasureService` (GDPR PII rewrite + soft-delete).
- **Backed by:** Hibernate → PostgreSQL `users`.

### Gotchas
- `existsByEmail` is a **best-effort** uniqueness check; it is *not* a substitute for the DB `UNIQUE` constraint. There's a classic TOCTOU race: two concurrent `createUser` calls can both pass `existsByEmail` and one will fail on insert with a constraint violation (surfaced as a DB/`DataIntegrityViolation`, not the friendly 409). The unique index is the real guarantee.
- The case-insensitivity is a property of the **column type**, not the query. If anyone ever migrates `email` off CITEXT, these methods silently become case-sensitive — a subtle auth/security regression.

---

## `control-panel-api/src/main/java/com/example/cp/users/UserDto.java`

### Responsibility
The **outbound** API representation of a user. It is a hand-written projection that deliberately *omits* sensitive/internal fields so they can never leak through a controller response.

### Public type

#### `record UserDto(UUID id, String email, String fullName, String status, boolean superAdmin, OffsetDateTime createdAt, OffsetDateTime lastLoginAt)`

What's **included** vs. the entity, and crucially what's **excluded**:

| Entity field | In DTO? | Why |
|--------------|---------|-----|
| `id`, `email`, `fullName`, `superAdmin`, `createdAt`, `lastLoginAt` | yes | Safe to expose to an authorized caller. |
| `status` | yes, **as `String`** | `enum` is flattened via `status.name()` so the JSON is a stable string token (`"ACTIVE"`) decoupled from the Java enum. |
| `passwordHash` | **no** | Never serialize a credential hash. The single most important omission. |
| `tokenVersion` | **no** | Internal session-revocation counter; no business meaning to clients. |
| `version` | **no** | Internal optimistic-lock counter. |

#### `static UserDto from(User u)`
Maps an entity → DTO. Note the **null-safe status mapping**: `u.getStatus() == null ? null : u.getStatus().name()`. In practice `status` is `NOT NULL`, but the guard avoids an NPE if a partially-built `User` is ever passed (defensive). Everything else is a straight getter copy.

### Collaborators
- **Used by:** `UserController.get(...)` and `UserController.patch(...)` wrap their `UserService` results in `UserDto.from(...)`.
- It does **not** appear in SCIM responses — SCIM has its own `ScimUser` shape (different contract).

### Gotchas
- It's a *record*, so it's immutable and field order is the JSON/constructor order. Reordering the record components is a wire-format change.
- There is no reverse mapping (DTO → entity) here; inbound writes use the small request records defined inside `UserController` instead. This keeps the read and write contracts separate.

---

## `control-panel-api/src/main/java/com/example/cp/users/UserService.java`

### Responsibility
The transactional business layer for users. **Every** user mutation in the system is supposed to funnel through here so it picks up password-policy validation, session revocation, and audit attribution consistently. It is the only class in the package annotated `@Transactional`.

### Dependencies (constructor-injected)

| Field | Type | Role |
|-------|------|------|
| `userRepository` | `UserRepository` | Persistence. |
| `passwordEncoder` | `PasswordEncoder` (Spring Security) | Bcrypt hashing (`encode`) and verification (`matches`). |
| `revocationStore` | `SessionRevocationStore` | Write-through of the new `token_version` to the Redis fast-path cache. |
| `passwordPolicy` | `PasswordPolicy` | Strength/denylist validation before any hash. |

### Methods

#### `User createUser(String email, String fullName, String password)` — `@Transactional`
Creates an `ACTIVE`, non-super-admin user.

Flow:
1. Reject blank/null email → `ApiException.badRequest` (400). *(Format isn't validated here; the DB/controllers handle that.)*
2. `passwordPolicy.validate(password)` → 400 on weak/common password. **Done before the existence check** so a weak password is rejected even for a would-be-duplicate email (no information leak about which check failed first matters less than always enforcing policy).
3. `existsByEmail(email)` → `ApiException.conflict` (409) if taken.
4. `AuditContext.set("user.created")`.
5. Build the entity with `Ids.newId()` (UUIDv7), bcrypt-hashed password, `status=ACTIVE`, `superAdmin=false`, `createdAt=now()`.
6. `save`, then `AuditContext.setTarget("user", savedId)` so the audit row points at the new user.

WHY it exists: it's the single sanctioned creation path. SCIM provisioning calls it (with a random password) precisely to inherit policy + audit, rather than inserting rows itself.

Edge cases / gotchas:
- `tokenVersion` defaults to `0` (builder default); a freshly created user is at version 0.
- The `existsByEmail` race (see repository gotchas) means a near-simultaneous duplicate can slip past step 3 and fail at the unique index in step 5/commit.
- Audit target is set *after* save (needs the id), so if `save` throws, the audit row won't be (mis)attributed.

#### `User get(UUID id)` — `@Transactional(readOnly = true)`
`findById` or throw `ApiException.notFound` (404). The internal building block reused by nearly every mutator below (so they all get the same 404 semantics). Read-only tx is a perf/intent hint to Hibernate (no dirty-checking flush).

#### `User getByEmail(String email)` — `@Transactional(readOnly = true)`
`findByEmail` or 404. Used by callers that key on email (case-insensitively, via CITEXT).

#### `User updateProfile(UUID id, String fullName)` — `@Transactional`
Loads the user, and **only if `fullName != null`** sets it (a null means "don't touch", supporting partial PATCH semantics). Sets audit action `user.updated` + target, saves, returns the entity. Note it cannot change email, status, password, or super-admin — by design this is the *profile* surface only.

#### `void changePassword(UUID id, String oldPassword, String newPassword)` — `@Transactional`
The **self-service** password change (old password required).

```
validate(newPassword)                       // policy first → 400 if weak
u = get(id)                                  // 404 if missing
if hash == null OR !matches(old, hash) → 400 "Old password is incorrect"
u.passwordHash = encode(newPassword)
revokeAllSessions(u)                         // bump token_version → log out everywhere
audit "user.password.changed"
save
```

Security notes:
- The **null-hash guard** matters: an SSO/SCIM user with no password (or an erased user) cannot have their password "changed" by guessing — `matches` is never reached with a null hash.
- Old-password mismatch and missing-hash both return the same generic 400, avoiding an oracle that reveals whether a password is set.
- Changing the password **revokes all sessions** — including the caller's current one — so the client must re-authenticate afterward. This is intentional (a password change should invalidate anything an attacker may have stolen).

#### `void setPassword(UUID id, String newPassword)` — `@Transactional`
The **administrative / reset** password set (no old password). Same as `changePassword` minus the verification step. Validates policy, sets the hash, `revokeAllSessions`, audits `user.password.reset`, saves.

WHY both exist: `changePassword` is for an authenticated user changing their own; `setPassword` backs the **forgot-password reset** flow. `AuthController` calls `userService.setPassword(...)` after validating a reset token, and there's an explicit comment there that `setPassword` *already* bumps the token-version, so the reset path must **not** bump again (double-bump bug guard).

#### `void deactivate(UUID id)` — `@Transactional`
Sets `status=SUSPENDED`, revokes all sessions, audits `user.deactivated`, saves. Reversible (someone can set the user back to `ACTIVE` via another path — SCIM does exactly this). Revoking sessions ensures a suspended user is immediately kicked out, not merely blocked at next login.

#### `void delete(UUID id)` — `@Transactional`
**Soft delete.** Sets `status=DELETED` (not a row delete), revokes sessions, audits `user.deleted`, saves. The inline comment ties this to the `DELETED` enum + CHECK constraint: there is intentionally no hard delete (preserves referential integrity with `org_members`, audit rows, etc., and supports compliance retention). Actual PII removal is a separate concern handled by `ErasureService` (GDPR).

#### `void touchLastLogin(UUID id)` — `@Transactional`
Best-effort `lastLoginAt = now()` update, using `findById(...).ifPresent(...)` so a missing user is a silent no-op (no 404) — appropriate for a post-login side effect that should never break the login response. **No audit, no token-version bump** (it's not a security-relevant mutation). Called by the auth layer after a successful authentication.

#### `private void revokeAllSessions(User u)`
The bulk session-revocation primitive shared by `changePassword`, `setPassword`, `deactivate`, `delete`.

```
v = u.tokenVersion + 1
u.tokenVersion = v                 // staged on the entity; CALLER must save() to persist
try { revocationStore.setTokenVersion(u.id, v); }  // write-through to Redis
catch (Exception e) { log.warn(...) }              // swallow — DB is source of truth
```

Critical contract details:
- **It does NOT save.** It mutates `tokenVersion` in memory; the calling method is responsible for `userRepository.save(u)`. Every caller does. The Javadoc says so explicitly. If you write a new mutator that calls `revokeAllSessions` but forgets to save, the bump is lost on commit.
- **Redis is best-effort.** The DB column is authoritative. A Redis failure is logged and swallowed — the revocation still takes effect via the persisted column (the auth filter falls back to the DB `token_version` on a Redis cache miss, where `currentTokenVersion` returns `-1`). This is a deliberate availability/consistency trade-off: a Redis outage must not block security-critical revocation.
- Because it runs inside the surrounding `@Transactional` method, the DB bump and the audit/status change commit atomically; the Redis write is a non-transactional side effect (so a tx rollback *after* the Redis write would leave Redis ahead of the DB — harmless, since "ahead" only over-revokes, failing safe).

### Collaborators
- **Calls:** `UserRepository`, `PasswordPolicy`, `PasswordEncoder`, `SessionRevocationStore`, `AuditContext`, `Ids`, `ApiException`.
- **Called by:** `UserController` (CRUD/profile/change-password), `AuthController` (`setPassword` on reset), `ScimService` (`createUser`, `deactivate`), and conceptually any provisioning path. Note `SsoProvisioningService` and `ErasureService` go to the **repository** directly for their special cases (JIT create with separate audit; PII null-out), and both replicate the `token_version + 1` bump manually — a place to watch for drift from the canonical `revokeAllSessions` logic.

### Gotchas
- The "must save after `revokeAllSessions`" contract is the single biggest footgun. Treat `revokeAllSessions` + `save` as an inseparable pair.
- Policy is validated *before* loading the user in `changePassword`/`setPassword`, so a weak new password fails with 400 even for a non-existent id (the 404 is never reached). That's fine but means error ordering is "policy, then existence."

---

## `control-panel-api/src/main/java/com/example/cp/users/UserController.java`

### Responsibility
The REST surface for users under **`/api/v1/users`**. It is a thin adapter: authorize → validate → delegate to `UserService` → map to `UserDto`. All business rules live in the service; the controller's job is HTTP shape, RBAC, and input validation.

### Public type

#### `@RestController @RequestMapping("/api/v1/users") class UserController`
Constructor-injects `UserService`.

### Endpoints

| HTTP | Path | Method | Authorization | Body | Returns |
|------|------|--------|---------------|------|---------|
| GET | `/{id}` | `get` | `hasAuthority('user.read')` **OR** `#id == currentUserId()` | — | `UserDto` |
| PATCH | `/{id}` | `patch` | `hasAuthority('user.write')` **OR** `#id == currentUserId()` | `UpdateProfileRequest` | `UserDto` |
| POST | `/{id}/deactivate` | `deactivate` | `hasAuthority('user.write')` | — | `204 No Content` |
| POST | `/{id}/delete` | `delete` | `hasAuthority('user.write')` | — | `204 No Content` |
| POST | `/{id}/change-password` | `changePassword` | (none at annotation) — **enforced in code: must be self** | `ChangePasswordRequest` | `204 No Content` |

#### Method details

**`UserDto get(UUID id)`** — `@PreAuthorize("hasAuthority('user.read') or #id == T(com.example.cp.common.SecurityUtils).currentUserId()")`. The SpEL `#id` binds the path variable; the `T(...).currentUserId()` static call resolves the caller's id from the `SecurityContext`. Net effect: **an admin with `user.read` can read anyone; any authenticated user can read themselves.** Delegates to `userService.get(id)` → `UserDto.from`.

**`UserDto patch(UUID id, @Valid UpdateProfileRequest body)`** — same dual rule with `user.write`. So a user can edit their own profile (name) without holding the admin authority. Delegates to `userService.updateProfile(id, body.fullName())`. `@Valid` triggers the `@Size(max=255)` check on the request.

**`ResponseEntity<Void> deactivate(UUID id)`** / **`delete(UUID id)`** — **admin-only** (`user.write`, no self clause). You cannot deactivate/delete yourself via the self-rule; this is deliberate — self-service account suspension/deletion is not exposed here, and it prevents a confused-deputy where a low-privilege user nukes their own (or via IDOR, another) account. Both return `204`.

**`ResponseEntity<Void> changePassword(UUID id, @Valid ChangePasswordRequest body)`** — Notably has **no `@PreAuthorize`**; instead it enforces "self" imperatively:
```java
AuthenticatedUser me = SecurityUtils.requireUser();   // 401 if unauthenticated
if (!me.userId().equals(id)) throw ApiException.forbidden("Can only change own password");  // 403
userService.changePassword(id, body.oldPassword(), body.newPassword());
```
WHY imperative rather than `@PreAuthorize`: the rule is *strictly* "self only" with **no admin override** — an admin must use the reset path (`setPassword`, which doesn't require the old password), not this endpoint (which does). Expressing "self only, never admin" is clearer in code, and it guarantees no `user.write` holder can change another user's password by knowing a (guessed) old password. `requireUser()` throws 401 if there's no authenticated principal; the id mismatch throws 403.

### Request DTOs (nested records)

#### `record UpdateProfileRequest(@Size(max = 255) String fullName)`
Only field is the display name; `@Size(max=255)` matches the `full_name VARCHAR(255)` column. `null` is allowed (and means "no change" downstream).

#### `record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank @Size(min = 8, max = 255) String newPassword)`
`oldPassword` must be present; `newPassword` is bean-validated to 8–255 chars **at the edge**. Note this is a *looser* gate than the real policy: `PasswordPolicy.MIN_LENGTH` is **12** with character-class + denylist rules. So the bean-validation `min=8` is just a cheap first filter; the authoritative check is `passwordPolicy.validate(...)` inside `UserService.changePassword`, which will still reject an 8–11 char password with a 400. (A minor inconsistency worth knowing: a client could pass bean-validation yet fail the policy.)

### Collaborators
- **Calls:** `UserService`, `UserDto`, `SecurityUtils` (`requireUser`), `AuthenticatedUser`, `ApiException`.
- **Called by:** the admin-ui SPA and any API client; routed through the security filter chain (`JwtAuthFilter` populates the `AuthenticatedUser` principal that the `@PreAuthorize` SpEL and `requireUser()` rely on).

### Security model recap & gotchas
- The recurring **"`hasAuthority(...)` OR self"** pattern is the IDOR-safe way to let users touch their own record without granting admin scope. It was a direct response to the audit's tenant-isolation/IDOR findings — without the self-clause you'd either over-expose (drop authz) or under-serve (force admin for self-service).
- `change-password` is the one endpoint where authz is *not* declarative — don't "tidy" it into a `@PreAuthorize` that adds an admin override; that would reintroduce the password-change-by-admin-knowing-old-password hole the imperative check exists to prevent.
- The controller never returns the password hash or token-version because it only ever serializes `UserDto`.
- There is **no create-user endpoint here.** User creation is driven by registration/provisioning flows (auth, SCIM, SSO) that call `UserService.createUser` — the `/api/v1/users` surface is read/update/lifecycle only.
