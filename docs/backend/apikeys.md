# `com.example.cp.apikeys` — Programmatic API Keys

## Module overview

This package implements **long-lived, programmatic API keys** that customer machines (CI jobs, the bundled Docker app's sidecar tooling, scripts) use to call the control-panel REST API without a human SSO/JWT session. A key is a single opaque secret string of the form `cp_<base32>` that is shown to the operator **exactly once** at creation time and **never stored in plaintext** — only a SHA-256 hash and a short non-secret prefix are persisted. Callers present the key on every request via `Authorization: ApiKey <token>`; a dedicated Spring Security filter resolves it to a synthetic, **org-bound** principal carrying a tightly constrained set of read-only scopes.

The package is deliberately small (six files) but it sits on three security-sensitive seams that the audit (P0/P1) hardened, and which a new engineer must understand before touching anything here:

1. **Scope allow-list at creation** — a key can only ever be minted with a scope from a configurable org-scoped allow-list, so it can never carry a global/cross-org or write authority.
2. **Tenant binding at authentication** — the resolved principal records the key's owning `orgId`; the central `TenantAccessChecker` only grants access when the *target* resource's org equals the key's bound org, and denies all management (write) operations for keys outright.
3. **Optimistic-lock + guarded conditional UPDATEs** — `verify()` (which stamps `last_used_at`) and `revoke()` (which stamps `revoked_at`) are written so that a concurrent "touch" can never silently resurrect a key that another thread just revoked (the P1-7 lost-update fix).

| File | Type | Responsibility |
|------|------|----------------|
| `ApiKey.java` | JPA `@Entity` | The `api_keys` row: hash, prefix, scopes JSON, lifecycle timestamps, `@Version`. |
| `ApiKeyRepository.java` | Spring Data JPA repo | CRUD + lookups by prefix/hash/org + two **guarded conditional UPDATE** queries. |
| `ApiKeyService.java` | `@Service` | Issuance (`create`), authentication (`verify`), revocation (`revoke`), listing, crypto helpers, scope allow-list enforcement. |
| `ApiKeyController.java` | `@RestController` | REST surface under `/api/v1/orgs/{orgId}/api-keys` (create / list / revoke) with `@PreAuthorize` tenant checks. |
| `ApiKeyAuthFilter.java` | Servlet filter (`OncePerRequestFilter`) | Parses `Authorization: ApiKey …`, calls `verify()`, builds and installs the `ApiKeyAuthentication` principal. |
| `ApiKeyFilterConfig.java` | `@Configuration` | Disables the filter's *servlet* auto-registration so it runs **only** inside the Spring Security chain. |

---

## File: `ApiKey.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKey.java`
**Responsibility:** The JPA entity mapping the `api_keys` table. It is the single source of truth for a key's stored shape and lifecycle state.

### Class `ApiKey` (`@Entity @Table(name = "api_keys")`)

Lombok-generated (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`), so there is no hand-written behaviour — the interesting content is the column mapping and the two Hibernate annotations on the class.

#### Fields

| Field | Column | Notes |
|-------|--------|-------|
| `UUID id` | `id` (PK) | Assigned by the application via `Ids.newId()` (time-ordered UUIDv7), **not** DB-generated. |
| `long version` | `version` | `@Version` optimistic-lock counter. Incremented on every update. |
| `UUID orgId` | `org_id` | The owning org — the **tenant binding** that authorization keys off. Never null. |
| `String name` | `name` | Human label (nullable). |
| `String keyHash` | `key_hash` | SHA-256 **hex** of the full plaintext key. The secret is never stored; only this. |
| `String keyPrefix` | `key_prefix` (len 16) | First 8 chars of the plaintext (`cp_xxxxx`). Non-secret; used as the lookup index so we don't scan all hashes. |
| `String scopesJson` | `scopes_json` (`jsonb`) | JSON array of granted scope strings. Mapped with `@JdbcTypeCode(SqlTypes.JSON)`. |
| `OffsetDateTime lastUsedAt` | `last_used_at` | Stamped on each successful `verify()`; nullable until first use. |
| `OffsetDateTime createdAt` | `created_at` | Set at issuance. |
| `OffsetDateTime revokedAt` | `revoked_at` | Null ⇒ live; non-null ⇒ revoked. This is the lifecycle flag every guarded query keys off. |

#### Class-level annotations — the gotcha that matters

```java
@DynamicUpdate           // emit UPDATE of only the changed columns, not a full re-persist
@Version (on `version`)  // optimistic lock; concurrent updates fail-fast
```

- **`@DynamicUpdate`** makes Hibernate emit an `UPDATE` touching **only the dirty columns** rather than re-writing every column. This is not a micro-optimization — it is a *correctness* measure. If a `save()` of the whole entity were emitted, it would re-write a now-stale `revoked_at = NULL` and silently undo a `revoke()` that committed between this caller's read and write. `@DynamicUpdate` plus the guarded queries (see repository) close that window.
- **`@Version`** keeps the optimistic-lock counter monotonic, so any concurrent full-entity update conflicts fail-fast (`OptimisticLockException`) instead of producing a lost write.

> **New-engineer note:** Both the service and the repository deliberately avoid `repo.save(existingKey)` for `verify`/`revoke`; they go through the targeted `@Modifying` queries in the repository. The `setLastUsedAt(...)` call in `verify()` only mutates the in-memory copy returned to the caller — the persisted write was already done by the guarded UPDATE.

**Collaborators:** persisted/loaded by `ApiKeyRepository`; built by `ApiKeyService.create`; read by `ApiKeyController.ApiKeyDto.from` and by `ApiKeyAuthFilter` (via the service).

---

## File: `ApiKeyRepository.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyRepository.java`
**Responsibility:** Persistence gateway. Beyond standard `JpaRepository<ApiKey, UUID>` CRUD, it supplies the lookups used during authentication/listing and the **two guarded conditional UPDATEs** that are the heart of the lost-update fix.

### Interface `ApiKeyRepository extends JpaRepository<ApiKey, UUID>`

#### Derived query methods

| Method | Purpose | Caller |
|--------|---------|--------|
| `List<ApiKey> findByOrgId(UUID orgId)` | All keys for an org (unpaged). | `ApiKeyService.listForOrg(orgId)` |
| `Page<ApiKey> findByOrgId(UUID orgId, Pageable)` | Paged variant for the listing endpoint. | `ApiKeyService.listForOrg(orgId, pageable)` |
| `List<ApiKey> findByKeyPrefix(String keyPrefix)` | **The authentication index.** Returns all keys sharing the 8-char prefix (collisions are possible but rare). | `ApiKeyService.verify` |
| `Optional<ApiKey> findByKeyHash(String keyHash)` | Lookup by full hash. Present for completeness; the live verify path uses prefix + constant-time compare instead. | (not on the hot path) |

> **Why prefix-then-hash, not hash directly?** `findByKeyPrefix` narrows to a tiny candidate set using a non-secret indexed column, and the service then does a **constant-time** hash comparison over those candidates. Querying by hash directly would still work, but the prefix approach keeps the hash comparison in application code where the timing-safe equality lives, and it mirrors how the key looks to a human (the prefix is what's shown in the UI).

#### Guarded conditional UPDATE: `touchLastUsedIfActive`

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update ApiKey k
           set k.lastUsedAt = :now, k.version = k.version + 1
         where k.id = :id and k.revokedAt is null
        """)
int touchLastUsedIfActive(@Param("id") UUID id, @Param("now") OffsetDateTime now);
```

- **What it does:** updates **only** `last_used_at` (and bumps `version`) and **only while the key is still live** (`revoked_at IS NULL`). Returns the row count: `1` = touched, `0` = the key was concurrently revoked.
- **Why it exists:** this is the read-side half of the P1-7 lost-update fix. Because the `WHERE` clause re-checks `revoked_at IS NULL` *atomically at write time*, a touch can never resurrect a key that `revokeIfActive` committed in the gap between `verify()`'s read and its write. The `0`-row result is the signal the service uses to refuse authentication in that race (see `verify`).
- **Edge case / concurrency:** `clearAutomatically = true` clears the persistence context after the bulk update so subsequent reads of the entity don't see the now-stale managed copy; `flushAutomatically = true` flushes pending changes first so the update sees a consistent state. The `version` bump keeps the JPA optimistic counter monotonic even though this is a bulk DML statement that bypasses entity dirty-checking.

#### Guarded conditional UPDATE: `revokeIfActive`

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update ApiKey k
           set k.revokedAt = :now, k.version = k.version + 1
         where k.id = :id and k.orgId = :orgId and k.revokedAt is null
        """)
int revokeIfActive(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("now") OffsetDateTime now);
```

- **What it does:** stamps `revoked_at` **only while live**, scoped to the owning org. Returns `1` if it revoked, `0` if already revoked (idempotent) or the id/org didn't match.
- **Why it exists:** the write-side half of the same fix. Because it sets `revoked_at` in a single atomic UPDATE (not a load-then-`save`), a concurrent `touchLastUsedIfActive` cannot clobber it back to NULL. The extra `org_id = :orgId` predicate is defence in depth — the service has already verified ownership, but including it here means the statement is incapable of revoking another org's key even if called wrongly.
- **Idempotency:** calling it twice is safe; the second call matches `0` rows because `revoked_at` is no longer NULL.

**Collaborators:** used exclusively by `ApiKeyService`. The two `@Modifying` queries are what let the entity's `@DynamicUpdate`/`@Version` annotations actually deliver their guarantee.

---

## File: `ApiKeyService.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyService.java`
**Responsibility:** All business logic for API keys — minting (with scope allow-list enforcement), authenticating a presented key, revoking, listing, scope parsing, and the crypto primitives (random generation, SHA-256, base32 encoding, constant-time compare).

### Class `ApiKeyService` (`@Service`)

#### Static constants & fields

| Member | Meaning |
|--------|---------|
| `static final SecureRandom RNG` | CSPRNG used to generate 32 random bytes per key. Shared/static (thread-safe). |
| `static final String BASE32_ALPHABET` | RFC-4648 base32 alphabet (`A–Z2–7`); the generated key is lower-cased. |
| `ApiKeyRepository repo` | Persistence. |
| `ObjectMapper mapper` | Local Jackson mapper for (de)serializing `scopes_json`. |
| `Set<String> creatableScopes` | **The org-scoped allow-list.** Immutable copy taken in the constructor. |

#### Constructor — the scope allow-list source

```java
public ApiKeyService(ApiKeyRepository repo,
        @Value("${app.api-keys.creatable-scopes:usage.ingest,usage.read,license.read}")
        Set<String> creatableScopes) { ... }
```

- Reads the allow-list from config property **`app.api-keys.creatable-scopes`**, defaulting to `usage.ingest, usage.read, license.read` — three **read/ingest-only** scopes. The constructor takes a defensive `Set.copyOf(...)` (and tolerates a null injection by falling back to `Set.of()`), so the allow-list is immutable for the bean's lifetime.
- **Why it exists:** this is the single guardrail that prevents an operator from minting a key carrying a dangerous authority. Even though the operator creating the key may themselves hold write/global authorities, the *key* they create is capped to this list — it can never be granted `subscription.read`, `subscription.write`, `license.issue`, `license.revoke`, `apikey.write`, or any `*.write`.

#### `record CreateResult(ApiKey apiKey, String plaintextKey)`

Carries the persisted entity **plus** the one-time plaintext back to the controller. The plaintext lives only in this transient record and the HTTP response; it is never stored.

#### `CreateResult create(UUID orgId, String name, Set<String> scopes)` — `@Transactional`

Flow:

1. **Normalize** requested scopes into a `LinkedHashSet` (preserves order, dedups, null-safe).
2. **Enforce the allow-list:** for each requested scope, reject (`ApiException.badRequest`) if it is null or not in `creatableScopes`. This is the throw-on-first-violation gate described above.
3. **Generate the secret:** 32 random bytes from `RNG` → base32 → prefix with `"cp_"` ⇒ the plaintext key.
4. **Derive the prefix** = first 8 chars (guards the `< 8` edge case, though `cp_` + base32 is always longer).
5. **Hash** the full plaintext with `sha256Hex`.
6. **Serialize** the scope set to JSON (`scopes_json`). A `JsonProcessingException` is surfaced as `badRequest`.
7. **Build** the `ApiKey` (id from `Ids.newId()`, `createdAt = now`) and `repo.save(...)`.
8. **Emit audit context:** `AuditContext.set("apikey.created")`, target `api_key`/id, payload with `org_id` and the non-secret `prefix`. (The controller also re-sets the action/target; harmless overlap — see controller notes.)
9. Return `CreateResult(saved, plaintext)`.

- **Security:** the plaintext is generated from a CSPRNG, never logged, and only the hash + prefix persist. The audit payload deliberately records only the **prefix**, never the secret.
- **Edge case:** an empty/null scope set is *allowed* (it passes the loop vacuously) and yields a key with no scopes — authenticable but authorized for nothing scope-gated.

#### `Optional<ApiKey> verify(String rawKey)` — `@Transactional`

The authentication core, called by `ApiKeyAuthFilter`.

1. **Reject** null or `< 9`-char tokens (need at least the 8-char prefix + 1) → `Optional.empty()`.
2. Compute `prefix = rawKey[0..8]` and `hash = sha256Hex(rawKey)`.
3. `candidates = repo.findByKeyPrefix(prefix)`.
4. For each candidate:
   - **Skip revoked** (`getRevokedAt() != null`).
   - **Constant-time compare** the computed hash against the stored hash (`constantTimeEquals`). On match:
     - Call `repo.touchLastUsedIfActive(id, now)`.
     - **If `touched == 0`** (the key was concurrently revoked between the `revoked_at != null` check and the UPDATE) → return `Optional.empty()`. *The request must not authenticate in that race.*
     - Otherwise set the in-memory `lastUsedAt` and return `Optional.of(k)`.
5. No match → `Optional.empty()`.

- **Why the guarded touch + 0-row check:** see `ApiKey` and the repository. The naive alternative (`k.setLastUsedAt(now); repo.save(k);`) would re-persist a stale `revoked_at = NULL` and undo a concurrent revoke. This is the P1-7 lost-update fix, regression-tested by `revokeIsNotUndoneByConcurrentVerifyTouch` in `ApiKeyAuthFilterIT`.
- **Security — timing:** the hash comparison is constant-time so an attacker cannot learn the hash byte-by-byte via response timing. (Note: the per-character XOR loop is constant-time relative to *content* given equal length, but it short-circuits on differing *lengths*; since both operands are fixed-length SHA-256 hex, length is constant in practice.)
- **Edge case:** prefix collisions across multiple live keys are handled by iterating all candidates; only the one whose full hash matches authenticates.

#### `void revoke(UUID orgId, UUID id)` — `@Transactional`

1. If `orgId == null` → `ApiException.notFound("API key not found")`.
2. Load by id; if absent → `notFound`.
3. If the loaded key's `orgId` doesn't equal the caller's `orgId` → `notFound` (**not** `403`).
4. `repo.revokeIfActive(id, orgId, now)` — the guarded conditional UPDATE.
5. Audit: `apikey.revoked`, target `api_key`/id.

- **Why 404 not 403 for cross-org / unknown:** this is the IDOR fix. Returning `403` would disclose that a key with that id exists in *some other* org. Returning `404` makes an unknown id and a cross-org id indistinguishable, closing the cross-org existence-disclosure / cross-org-revoke hole.
- **Why look up first *and* pass org to the guarded UPDATE:** the explicit lookup guarantees a deterministic `404` for unknown/cross-org ids (a bare conditional UPDATE that no-ops would otherwise look like a silent success). The org predicate inside `revokeIfActive` is then redundant defence in depth.
- **Idempotency:** revoking an already-revoked key is a clean no-op (the UPDATE matches 0 rows) and still returns success.

#### `List<ApiKey> listForOrg(UUID orgId)` / `Page<ApiKey> listForOrg(UUID orgId, Pageable)` — `@Transactional(readOnly = true)`

Thin pass-throughs to `repo.findByOrgId(...)`. The controller uses the paged overload; the unpaged one is used by `ApiKeyAuthFilterIT` and other internal callers.

#### `Set<String> parseScopes(ApiKey key)`

Deserializes `scopes_json` into a `Set<String>`. Returns `Set.of()` for null/blank JSON or on **any** parse exception (fail-safe to *no* scopes rather than throwing). Used by `ApiKeyAuthFilter` to project scopes into Spring Security authorities.

> **Gotcha:** the raw-type `mapper.readValue(json, Set.class)` produces an unchecked `Set` (elements are whatever Jackson infers, typically `String`). It's adequate here because downstream only stringifies elements into `SimpleGrantedAuthority`, but it is an unchecked conversion to be aware of.

#### Static crypto helpers (package-private, unit-tested directly)

| Method | Behaviour |
|--------|-----------|
| `static String sha256Hex(String input)` | SHA-256 of UTF-8 bytes, hex-encoded via `HexFormat`. Wraps the impossible `NoSuchAlgorithmException` as `IllegalStateException`. |
| `static boolean constantTimeEquals(String a, String b)` | Length-checked then XOR-accumulating equality. Returns `false` for nulls or unequal lengths; otherwise no early-out on content. |
| `static String base32(byte[] input)` | RFC-4648-style base32 over the byte stream (5-bit groups), lower-cased, no padding. Used only to render the random key body. |

**Collaborators:** depends on `ApiKeyRepository`, `ApiException`, `AuditContext`, `Ids`, Jackson `ObjectMapper`. Called by `ApiKeyController` (create/list/revoke) and `ApiKeyAuthFilter` (verify/parseScopes).

---

## File: `ApiKeyController.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyController.java`
**Responsibility:** The REST surface for managing keys, rooted at `/api/v1/orgs/{orgId}/api-keys`. It does no business logic itself — it delegates to `ApiKeyService` and applies tenant-scoped `@PreAuthorize` checks.

### Class `ApiKeyController` (`@RestController @RequestMapping("/api/v1/orgs/{orgId}/api-keys")`)

#### `POST /` → `CreateResponse create(UUID orgId, CreateRequest body)`

- **Authorization:** `@PreAuthorize("@tenantAccess.canManageOrg(#orgId)")` — requires a **human** OWNER/ADMIN of the org (or super-admin). API-key principals are denied (`canManageOrg` returns `false` for keys), so **you cannot use an API key to mint more API keys**.
- Delegates to `service.create(orgId, name, scopes)`, re-stamps `AuditContext` action/target, returns `CreateResponse.from(result)`.
- **The plaintext key is returned here and only here** (in the `key` field).

#### `GET /` → `PagedResponse<ApiKeyDto> list(UUID orgId, Integer page, Integer size)`

- **Authorization:** `@PreAuthorize("@tenantAccess.canAccessOrg(#orgId)")` — any member of the org (read access). An API key bound to this org also passes (read-level).
- **Pagination is server-enforced:** `PageRequestParams.of(page, size, "createdAt,desc")` caps `size` at `MAX_SIZE = 200`, defaults to `DEFAULT_SIZE = 20`, and sorts newest-first. A client cannot pull an unbounded list.
- Maps each entity through `ApiKeyDto::from` into a `PagedResponse` envelope (consistent with the audit/org/rbac listing idiom).

#### `DELETE /{id}` → `ResponseEntity<Void> revoke(UUID orgId, UUID id)`

- **Authorization:** `@PreAuthorize("@tenantAccess.canManageOrg(#orgId)")` — same management gate as create.
- Calls `service.revoke(orgId, id)` (which itself re-enforces org ownership → `404` on mismatch) and returns `204 No Content`.

#### Nested records (the DTO contract)

```java
record CreateRequest(@Size(max = 255) String name, Set<String> scopes) {}
```
Inbound body; `name` is length-validated via `@Valid`.

```java
record CreateResponse(UUID id, String name, String key, String keyPrefix,
                      Set<String> scopes, OffsetDateTime createdAt)
```
`from(CreateResult)` populates `key` with the **plaintext**. Note `scopes` is wired as `null` in `from(...)` — the response intentionally does not echo scopes here (the gotcha below).

```java
record ApiKeyDto(UUID id, UUID orgId, String name, String keyPrefix, String scopes,
                 OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt)
```
Listing projection. **Never includes `keyHash`** (no secret material leaves the server). Note `scopes` here is the **raw `scopes_json` string**, not a parsed array.

> **Gotchas for a new engineer:**
> - `CreateResponse.scopes` is always `null` — the create response shows the key, prefix, name, id and timestamp but not the granted scopes. If a client needs to confirm scopes post-create it must `GET` and read `ApiKeyDto.scopes` (the raw JSON string).
> - `AuditContext.set("apikey.created")` is called in *both* the service and the controller `create` — redundant but harmless (same action/target).

**Collaborators:** depends on `ApiKeyService`, `AuditContext`, `PageRequestParams`, `PagedResponse`, and the `tenantAccess` bean (`TenantAccessChecker`) for SpEL authorization.

---

## File: `ApiKeyAuthFilter.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyAuthFilter.java`
**Responsibility:** The Spring Security filter that turns an `Authorization: ApiKey <token>` header into an authenticated, org-bound `Authentication` in the `SecurityContext`. This is how a presented key actually authenticates a request.

### Class `ApiKeyAuthFilter extends OncePerRequestFilter` (`@Component`)

#### Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `HEADER` | `"Authorization"` | Header read. |
| `SCHEME` | `"ApiKey "` | Required scheme prefix (note the trailing space). |

#### `void doFilterInternal(request, response, chain)`

Flow:

1. Read the `Authorization` header. If it's absent or does **not** start with `"ApiKey "`, do nothing and continue the chain (a `Bearer …` JWT, for example, is left for `JwtAuthFilter`).
2. Strip the scheme, `trim()` the remainder → the raw token.
3. `service.verify(raw)`:
   - **Empty** ⇒ leave the `SecurityContext` untouched (request proceeds unauthenticated → downstream `@PreAuthorize`/entry point yields `401`/`403`).
   - **Present** ⇒ build the principal:
     - `scopes = service.parseScopes(key)`.
     - Map scopes → `List<SimpleGrantedAuthority>`.
     - `AuthenticatedUser principal = new AuthenticatedUser(null, "apikey:" + key.getId(), false, scopes, authorities, /*apiKey*/ true, /*apiKeyOrgId*/ key.getOrgId())`.
     - Wrap in `ApiKeyAuthentication(principal, authorities, key.getOrgId())`, `setAuthenticated(true)`, install via `SecurityContextHolder`.
4. Any exception from `verify`/parsing is **caught and logged at WARN** (`"ApiKey auth failed: …"`) — the filter **never** throws into the chain; a failed key just means "unauthenticated", not a `500`.
5. Always `chain.doFilter(...)`.

- **Why fail-open-to-unauthenticated (not error):** authentication failure must degrade to "no principal" so the normal authorization layer returns `401`/`403`. Throwing would leak a `500` and could be a DoS lever. The `try/catch` guarantees that.
- **Why the principal is `superAdmin = false`, `userId = null`, `apiKey = true`, with `apiKeyOrgId`:** this is the tenant binding. `TenantAccessChecker.canAccessOrg` only allows an API-key principal when `orgId.equals(apiKeyOrgId)`, and `canManageOrg` denies API keys outright. So even a perfectly valid key is confined to read/ingest operations on its own org — verified end-to-end by `apiKeyForOrgA_cannotIngestForOrgBJti…` in `ApiKeyAuthFilterIT`.
- **Ordering:** registered via `SecurityConfig` `http.addFilterBefore(apiKeyFilter, JwtAuthFilter.class)`, so an `ApiKey` header is resolved before JWT processing. A genuine API-key value presented under the `Bearer` scheme is **not** treated as an API key (the scheme check fails) and is rejected by the JWT filter as a malformed JWT (`wrongAuthScheme_isNotTreatedAsApiKey_401`).

#### Static nested class `ApiKeyAuthentication extends AbstractAuthenticationToken`

The concrete `Authentication` type for API-key requests.

| Member | Behaviour |
|--------|-----------|
| ctor `(AuthenticatedUser principal, List<GrantedAuthority> authorities, UUID orgId)` | Passes authorities to the superclass; stores principal + org. |
| `Object getCredentials()` | Returns `""` — the secret is never retained on the token. |
| `Object getPrincipal()` | The `AuthenticatedUser`. |
| `UUID getOrgId()` | Exposes the bound org (convenience; the binding also lives on the principal's `apiKeyOrgId`). |

- **Security:** `getCredentials()` deliberately returns an empty string so the plaintext key is not kept in memory on the security context after authentication.

**Collaborators:** depends on `ApiKeyService` (`verify`, `parseScopes`) and `AuthenticatedUser`. Wired into the security chain by `SecurityConfig`; its servlet auto-registration is suppressed by `ApiKeyFilterConfig`. Its principal is consumed by `TenantAccessChecker` and by `SecurityUtils.currentUser()`.

---

## File: `ApiKeyFilterConfig.java`

**Path:** `control-panel-api/src/main/java/com/example/cp/apikeys/ApiKeyFilterConfig.java`
**Responsibility:** A tiny but essential `@Configuration` that prevents the `@Component`-annotated `ApiKeyAuthFilter` from being **auto-registered as a plain servlet filter** by Spring Boot.

### Class `ApiKeyFilterConfig` (`@Configuration`)

#### `@Bean FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter)`

```java
FilterRegistrationBean<ApiKeyAuthFilter> reg = new FilterRegistrationBean<>(filter);
reg.setEnabled(false);   // do NOT register in the raw servlet container
return reg;
```

- **The problem it solves:** Spring Boot automatically registers any `Filter` bean (here `ApiKeyAuthFilter`, a `@Component`) into the servlet container's filter chain. That would make the filter run **twice** — once in the servlet chain and once inside the Spring Security chain (where `SecurityConfig` adds it via `addFilterBefore`). Double execution risks duplicate `last_used_at` touches, duplicate principal installation, and confusing ordering relative to CSRF/CORS/rate-limit filters.
- **The fix:** wrap the bean in a `FilterRegistrationBean` with `setEnabled(false)`, which tells Boot *not* to register it in the raw servlet container. The bean still exists in the context, so `SecurityConfig` can pull it (via `ObjectProvider<ApiKeyAuthFilter>`) and place it precisely in the security filter chain — and **only** there.

> **New-engineer note:** This is a standard Spring Boot idiom for "this is a `Filter` bean but I manage its placement myself." If you ever see the API-key filter running unexpectedly early or twice, check that this registration bean still exists and is `disabled`.

**Collaborators:** references `ApiKeyAuthFilter`; works in concert with `auth/SecurityConfig.java`, which is the *only* place the filter is actually installed.

---

## End-to-end flows

### 1. Creating a key (operator → REST)

```
POST /api/v1/orgs/{orgId}/api-keys   (human OWNER/ADMIN; @PreAuthorize canManageOrg)
  └─ ApiKeyService.create
       1. enforce scope allow-list (app.api-keys.creatable-scopes)   ← reject unknown/global/write scopes
       2. RNG 32 bytes → "cp_" + base32  (plaintext, shown once)
       3. persist {hash = sha256(plaintext), prefix, scopes_json, org_id, created_at}
       4. AuditContext: apikey.created
  └─ CreateResponse { id, name, key=<plaintext>, keyPrefix, createdAt }   ← only place plaintext escapes
```

### 2. Using a key (machine → any API endpoint)

```
Authorization: ApiKey cp_xxxxxxxx...
  └─ ApiKeyAuthFilter.doFilterInternal
       ├─ scheme != "ApiKey " ?  → skip, let JwtAuthFilter handle
       └─ ApiKeyService.verify(raw)
            ├─ findByKeyPrefix(prefix)               (indexed candidate lookup)
            ├─ skip revoked; constant-time hash compare
            ├─ touchLastUsedIfActive  → 0 rows? (raced a revoke) ⇒ Optional.empty ⇒ NOT authenticated
            └─ match ⇒ build AuthenticatedUser(apiKey=true, apiKeyOrgId=org) + ApiKeyAuthentication
  └─ downstream @PreAuthorize via TenantAccessChecker
       ├─ canAccessOrg(target): allowed only if target == apiKeyOrgId   (read/ingest, own org only)
       └─ canManageOrg(target): API keys ALWAYS denied                 (no writes)
```

### 3. Revoking a key

```
DELETE /api/v1/orgs/{orgId}/api-keys/{id}   (human OWNER/ADMIN)
  └─ ApiKeyService.revoke(orgId, id)
       ├─ null org / unknown id / cross-org id  → 404 "API key not found"   (no existence disclosure)
       └─ revokeIfActive(id, org, now)  → guarded UPDATE ... WHERE revoked_at IS NULL  (idempotent)
  └─ 204 No Content
```

### The lost-update guarantee (P1-7), as a race timeline

| Thread A (`verify` touch) | Thread B (`revoke`) | Outcome |
|---|---|---|
| reads key, sees `revoked_at = NULL` | | |
| | `revokeIfActive` commits `revoked_at = now` | key now revoked |
| `touchLastUsedIfActive` runs `WHERE revoked_at IS NULL` ⇒ **0 rows** | | touch is a no-op |
| sees `touched == 0` ⇒ returns `Optional.empty()` | | request **not** authenticated; revoke **not** undone |

The two guarded UPDATEs + `@DynamicUpdate` + `@Version` together make this race always resolve in favour of the revoke. Regression test: `revokeIsNotUndoneByConcurrentVerifyTouch`.

---

## How it fits the bigger picture

API keys are the **machine-to-machine authentication** path for the control-panel API, parallel to the human SSO/JWT path (`JwtAuthFilter`). They matter most for the **usage-ingest** loop: a customer's running app (or its sidecar) holds a key scoped to `usage.ingest`/`usage.read`/`license.read` and calls endpoints like `POST /api/v1/usage/ingest`, which require both the scope **and** that the key's bound org owns the target license's `jti` (resolved by `TenantAccessChecker.canIngestUsageForJti`). This package is the issuance/verification half; the offline Ed25519 `.lic` license verification lives in the separate `license-verifier` SDK and is unrelated to these REST credentials.

Security-wise this package embodies three of the audit's remediations in one place: the **scope allow-list** (no privilege escalation via minted keys), the **tenant binding + management-deny** in `TenantAccessChecker` (no cross-org IDOR, no key-driven writes), and the **guarded-update / optimistic-lock** pattern (no lost-update resurrection of a revoked key). A new engineer changing `verify`/`revoke` must preserve the conditional-UPDATE-plus-zero-row-check contract, and any change to creatable scopes must go through `app.api-keys.creatable-scopes` rather than loosening the controller's `@PreAuthorize`.
