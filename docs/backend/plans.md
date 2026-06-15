# `com.example.cp.plans` — Plan catalog & entitlement templates

## Module overview

The `plans` package is the **product catalog** of the control panel. A *plan* (e.g. `starter`, `pro`, `enterprise`) is the reusable template that defines *what a subscription is entitled to*: a set of **permission codes** (boolean capabilities such as `export.csv`, `api.v2`, `admin.sso.configure`) and a map of **feature values** (typed configuration such as `max_users: 50`, `ai_assistant: true`, `seats: 25`). A plan also carries a default license TTL (`defaultTtlDays`) and an `active` flag that gates whether new subscriptions can be opened against it.

Plans are *not* directly consumed by customer apps. Instead they sit at the **base of the entitlement-resolution pipeline**: when a license is minted, the licensing layer reads the plan's permissions + features as a *baseline*, the subscription layer applies per-subscription **overrides** on top, and the result is what gets signed into the Ed25519 `.lic` token. So everything in this package is the editable source-of-truth that ultimately decides what a customer's Docker app is allowed to do offline.

This package implements the full **plan editor backend**: a REST surface (`PlanController`), a transactional service (`PlanService`), three JPA entities mapped to three tables (`plans`, `plan_permissions`, `plan_features`), their repositories, and a read DTO (`PlanDto`). It is deliberately small and CRUD-shaped, but it has two security-relevant subtleties that dominate its design: (1) the **`ReplacePermissionsRequest` null-vs-empty contract** (the P0-2 data-loss fix), and (2) **paginated, bounded listing** (the P3 unbounded-result fix).

### Files documented (9)

| File | Kind | Role |
|------|------|------|
| `Plan.java` | `@Entity` | The plan row: code, name, tier, active, default TTL |
| `PlanPermission.java` | `@Entity` (composite PK) | One permission code granted by a plan |
| `PlanFeature.java` | `@Entity` (composite PK) | One feature key → JSON value for a plan |
| `PlanRepository.java` | Spring Data repo | CRUD + by-code + active-only (paged) queries for plans |
| `PlanPermissionRepository.java` | Spring Data repo | Per-plan permission rows; bulk delete-by-plan |
| `PlanFeatureRepository.java` | Spring Data repo | Per-plan feature rows; bulk delete-by-plan |
| `PlanDto.java` | `record` | Read model returned by the API (plan + permissions + features) |
| `PlanService.java` | `@Service` | All business logic: create/update/replace, detail assembly, lookups |
| `PlanController.java` | `@RestController` | REST surface `/api/v1/plans` + the request records |

---

## How it fits the bigger picture

```
  Admin UI (plan editor)
        │  POST/PATCH /api/v1/plans …
        ▼
  PlanController ──▶ PlanService ──▶ PlanRepository / PlanPermissionRepository / PlanFeatureRepository
                                          │  (plans, plan_permissions, plan_features tables)
                                          ▼
                          baseline permissions + features
        ┌─────────────────────────────────┴──────────────────────────────┐
        ▼                                                                  ▼
  SubscriptionService.resolveEntitlements(...)            SubscriptionService.create(...)
   (overlays per-subscription overrides on the plan         (validates plan.isActive(),
    baseline → ResolvedEntitlements)                          uses plan.getDefaultTtlDays()
        │                                                      for the subscription window)
        ▼
  LicenseClaimsBuilder
   (reads planService.getPermissions / getFeatures,
    clamps plan.getDefaultTtlDays() into the token `exp`)
        ▼
  Ed25519-signed .lic token  ──▶  customer Docker app (offline verify via SDK)
```

Concrete downstream consumers (verified in the codebase):

- **`com.example.cp.licenses.LicenseClaimsBuilder`** — calls `planService.getPermissions(planId)` and `planService.getFeatures(planId)` to seed the entitlement baseline, then calls `subService.resolveEntitlements(subId, basePerms, baseFeatures)` to apply overrides, and uses `plan.getDefaultTtlDays()` (clamped) to compute the token's `exp`.
- **`com.example.cp.subscriptions.SubscriptionService`** — on `create`, loads the plan, **rejects if `!plan.isActive()`**, and derives the subscription's `endsAt` from `plan.getDefaultTtlDays()` when the caller doesn't supply one. Also surfaces `plan.getCode()` into audit/outbox events and into `SubscriptionDto`.
- **`com.example.cp.licenses.ActivationService`** — reads `planService.getFeatures(planId).get("seats")` to cap concurrent activations (lease enforcement); a numeric `seats` feature becomes the seat limit.
- **`com.example.cp.billing.RatingService`** — references plans for rating/pricing.

The practical takeaway for a new engineer: **editing a plan changes the entitlements of every future license issued for every subscription on that plan.** Plan edits are not retroactive to already-issued `.lic` tokens (those are signed snapshots), but they take effect the next time a token is minted/renewed for a subscription on that plan.

---

## File-by-file

### `Plan.java`

**Responsibility:** JPA entity for the `plans` table — the catalog header row. Holds identity + descriptive metadata + the two policy knobs (`active`, `defaultTtlDays`). Permissions and features live in *separate* tables/entities, not here, so a `Plan` instance alone never carries its entitlements.

**Class `Plan`** (`@Entity @Table(name="plans")`, Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`)

| Field | Column | Notes |
|-------|--------|-------|
| `UUID id` | `id` (PK, not null) | Assigned in app code via `Ids.newId()` (time-ordered UUIDv7), **not** DB-generated even though the DDL has `DEFAULT gen_random_uuid()`. Because `@Id` is a non-generated assigned value, Hibernate treats a populated id as "detached" on `save`, doing a SELECT-then-INSERT/UPDATE. |
| `String code` | `code` (not null, **unique**) | Stable business key (`starter`, `pro`, …). The uniqueness is enforced both in DB (`UNIQUE`) and pre-checked in the service via `existsByCode`. Used everywhere as the human-facing handle and embedded into licenses/subscriptions. |
| `String name` | `name` | Display name (nullable). |
| `String description` | `description` | Free text (`TEXT` in DB; nullable). |
| `String tier` | `tier` | Pricing/segmentation tier. Defaulted to `code` by the service if omitted on create. |
| `boolean active` | `is_active` (not null) | Gate for new subscriptions. Note the Java field is `active` but the column is `is_active`; Lombok generates `isActive()`/`setActive(...)` (primitive-boolean convention). |
| `int defaultTtlDays` | `default_ttl_days` (not null) | Default license validity window in days. DB default 365; service default 365. Consumed by `LicenseClaimsBuilder` (clamped) and `SubscriptionService`. |
| `OffsetDateTime createdAt` | `created_at` (not null) | Set by the service (`OffsetDateTime.now()`), not relied upon from the DB default. |

**Gotchas / things to know**

- **No `@Version` field.** Unlike several other entities that gained optimistic locking in migration wave 18, `Plan` has none. Concurrent edits to the same plan are last-write-wins. For the permission/feature *child* tables this is mitigated by the delete-then-insert replace strategy (see `PlanService.replacePermissions`), but two simultaneous header `PATCH`es can clobber each other silently.
- The DB has a cascading FK from `plan_permissions`/`plan_features` to `plans(id) ON DELETE CASCADE`, but **there is no delete endpoint** in this package — plans can be created and updated but the API never deletes them (you'd deactivate via `active=false` instead).

---

### `PlanPermission.java`

**Responsibility:** JPA entity for the `plan_permissions` join/grant table. Each row says "plan *P* grants permission code *C*." There is no separate "permission" master entity in this package — permissions are just free-form string codes, and the set of legal codes is governed by the RBAC layer / seed data, not by a FK here.

**Class `PlanPermission`** (`@Entity @Table(name="plan_permissions")`)

- `@EmbeddedId Pk id` — the entire primary key is the composite `(planId, permissionCode)`. There are **no other columns**; the row's existence *is* the grant.

**Nested `@Embeddable` `PlanPermission.Pk implements Serializable`**

| Field | Column | Notes |
|-------|--------|-------|
| `UUID planId` | `plan_id` (not null) | FK to `plans.id` (`ON DELETE CASCADE` in DDL). |
| `String permissionCode` | `permission_code` (not null) | The capability string, e.g. `api.v2`. |

- Hand-written `equals`/`hashCode` over both fields. **This is mandatory for an `@EmbeddedId`**: JPA requires the composite key to implement value-equality so the persistence context can deduplicate and merge entities by key. Lombok's `@Builder` etc. don't generate these for an embeddable in a way Hibernate can rely on, hence the explicit `Objects.equals/hash`.

**Gotcha — column length mismatch.** The DB column is `VARCHAR(128)`, but the API contract (`ReplacePermissionsRequest`) validates each code with `@Size(max=64)`. So the API is stricter than storage: codes are capped at 64 chars at the edge even though the table could hold 128. There's no `@Column(length=...)` on the entity, so Hibernate's own DDL (if ever used instead of Liquibase) would default to 255 — another reason the canonical schema is the Liquibase changelog, not entity-generated DDL.

---

### `PlanFeature.java`

**Responsibility:** JPA entity for `plan_features` — typed feature configuration stored as JSONB. Each row is one `(plan, featureKey) → JSON value`. Unlike permissions (pure booleans-by-presence), features carry an arbitrary JSON value, so a feature can be a number (`max_users: 50`), a boolean (`ai_assistant: true`), a string, or a nested object/array.

**Class `PlanFeature`** (`@Entity @Table(name="plan_features")`)

| Member | Column | Notes |
|--------|--------|-------|
| `@EmbeddedId Pk id` | `(plan_id, feature_key)` | Composite PK — one value per key per plan. |
| `String valueJson` | `value_json` (not null, `jsonb`) | The feature value, stored as a **JSON string**. Annotated `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition="jsonb"` so Hibernate binds the Java `String` to a Postgres `jsonb` column (Postgres will reject malformed JSON at write time). |

**Nested `@Embeddable` `PlanFeature.Pk implements Serializable`** — same shape/rationale as `PlanPermission.Pk`: `UUID planId` + `String featureKey`, with explicit `equals`/`hashCode`.

**Key design point — the value is stored as a serialized JSON *string*, not a parsed structure.** `PlanService.replaceFeatures` does `objectMapper.writeValueAsString(value)` before persisting, and `getFeatures` does `objectMapper.readValue(json, Object.class)` to parse it back. So:

- `max_users = 50` is stored as the literal `jsonb` `50` (a JSON number), and re-read as a Java `Integer`/`Long`.
- `ai_assistant = true` is stored as `true`, re-read as a Java `Boolean`.
- A nested object `{"limits":{"rps":100}}` round-trips through the JSON column intact.

**Gotcha — `feature_key` length.** DB is `VARCHAR(64)`; there's no `@Size` validation on feature keys at the controller (`ReplaceFeaturesRequest` is unvalidated, see below), so an over-long key would fail at the DB layer with a constraint error surfaced as a 500-ish error rather than a clean 400. Keep feature keys short.

---

### `PlanRepository.java`

**Responsibility:** Spring Data JPA repository for `Plan`. `@Repository interface PlanRepository extends JpaRepository<Plan, UUID>`.

| Method | Purpose / WHY |
|--------|---------------|
| `Optional<Plan> findByCode(String code)` | Lookup by business key. Used by `PlanService.getByCode` (and indirectly anywhere a plan is referenced by `code` rather than `id`, e.g. activation flows). |
| `boolean existsByCode(String code)` | Cheap pre-insert uniqueness check in `createPlan`; lets the service throw a clean `409 Conflict` instead of bubbling a DB unique-constraint violation. (Note: this is a check-then-act, so it's racy under concurrency — the DB `UNIQUE` constraint is the real guard; two simultaneous creates with the same code would have one fail at flush.) |
| `List<Plan> findAllByActiveTrue()` | Unpaged active-only list. Retained for internal/legacy callers; the controller uses the paged variant. |
| `Page<Plan> findAllByActiveTrue(Pageable pageable)` | **Paged** active-only list — the P3 bounded-result path used by `GET /api/v1/plans?activeOnly=true`. |

Inherited from `JpaRepository`: `findById`, `findAll()`, `findAll(Pageable)`, `save`, etc., all used by the service.

---

### `PlanPermissionRepository.java`

**Responsibility:** `@Repository interface PlanPermissionRepository extends JpaRepository<PlanPermission, PlanPermission.Pk>`. Note the second type parameter is the **composite `Pk`**, not a plain UUID.

| Method | Purpose / WHY |
|--------|---------------|
| `List<PlanPermission> findByIdPlanId(UUID planId)` | Fetch all permission rows for a plan. The `IdPlanId` derived-query path navigates `PlanPermission.id.planId` (the embedded key's field). Used by `PlanService.getPermissions`. |
| `void deleteByIdPlanId(UUID planId)` | Bulk-delete every permission row for a plan — the first half of the **delete-then-reinsert "replace"** strategy. WHY a dedicated derived delete: it issues a single `DELETE … WHERE plan_id = ?` instead of loading entities first. |

`saveAll(...)` (inherited) does the reinsert half.

---

### `PlanFeatureRepository.java`

**Responsibility:** identical shape to `PlanPermissionRepository` but for `PlanFeature` keyed by `PlanFeature.Pk`.

| Method | Purpose / WHY |
|--------|---------------|
| `List<PlanFeature> findByIdPlanId(UUID planId)` | All feature rows for a plan; used by `getFeatures`. |
| `void deleteByIdPlanId(UUID planId)` | Bulk delete for the replace strategy in `replaceFeatures`. |

---

### `PlanDto.java`

**Responsibility:** Immutable read model returned by every read/write endpoint. It is the *denormalized* view that flattens the three tables into one JSON object: the plan header plus its full permission list and feature map. This is exactly the shape the admin UI's plan editor renders.

```java
public record PlanDto(
    UUID id, String code, String name, String description, String tier,
    boolean active, int defaultTtlDays, OffsetDateTime createdAt,
    List<String> permissions,        // sorted permission codes
    Map<String, Object> features     // featureKey -> parsed JSON value
) { ... }
```

**Static factory `PlanDto basic(Plan p)`** — builds a DTO from a `Plan` with **empty** `permissions`/`features` (`List.of()` / `Map.of()`). WHY it exists: a lightweight projection for callers that only need the header and want to avoid the extra two queries that `getPermissions`/`getFeatures` would issue. *Note:* within this package the controller/service paths actually use the full-detail constructor (via `getPlanWithDetails`/`toDtos`), so `basic(...)` is a convenience/utility factory for lighter callers rather than the main path.

**Gotcha — N+1 on listing.** The full-detail listing (`toDtos`) calls `getPermissions` and `getFeatures` *per plan*, i.e. 2 extra queries per plan. That's why the controller list is paginated/capped — it bounds the N. For the small seeded catalog this is fine; for a large catalog it's the obvious optimization target (a join-fetch or batch query).

---

### `PlanService.java`

**Responsibility:** All business logic for the plan editor. It owns transactions, the create/update rules, the delete-then-reinsert "replace" semantics for permissions and features, JSON (de)serialization of feature values, and assembly of `PlanDto` detail objects. Every public method is `@Transactional` (writes) or `@Transactional(readOnly = true)` (reads).

**Collaborators:** `PlanRepository`, `PlanPermissionRepository`, `PlanFeatureRepository`, Jackson `ObjectMapper` (for feature JSON), plus `com.example.cp.common.{ApiException, AuditContext, Ids}`.

#### Write methods

**`Plan createPlan(code, name, description, tier, Integer defaultTtlDays, Boolean active, Collection<String> permissions, Map<String,Object> features)`**

Flow:
1. Reject blank `code` → `ApiException.badRequest`.
2. Reject duplicate `code` via `existsByCode` → `ApiException.conflict` (409).
3. Build the `Plan` with **defaults applied in code**: `id = Ids.newId()`; `tier = (tier == null ? code : tier)`; `active = (active == null ? true : active)`; `defaultTtlDays = (defaultTtlDays == null ? 365 : defaultTtlDays)`; `createdAt = now()`.
4. `planRepo.save(p)`.
5. If `permissions != null`, delegate to `replacePermissions(savedId, permissions)`. If `features != null`, delegate to `replaceFeatures(savedId, features)`. (Null = "not provided, leave empty"; the request records use boxed/nullable types precisely so the service can distinguish "absent" from "empty".)
6. Emit audit breadcrumbs: `AuditContext.set("plan.created")` + `setTarget("plan", id)`.

WHY defaults live in the service rather than the entity/DDL: the controller passes the raw boxed request fields straight through, so the service is the single place that decides "no tier supplied ⇒ tier := code", keeping the DB defaults and the API defaults from drifting.

**`Plan updatePlan(UUID id, name, description, tier, Integer defaultTtlDays, Boolean active)`** — partial (PATCH) update. Loads the plan (404 if missing) and applies **only non-null fields** (`if (x != null) p.setX(x)`). This is the classic PATCH semantic: a field omitted in the JSON binds to `null` and is left unchanged. Permissions/features are deliberately **not** touched here — they have their own dedicated replace endpoints. Audit: `plan.updated`.

> Edge case: because PATCH uses null-means-skip, there is **no way to clear `name`/`description` back to null via this endpoint** — sending `null` is indistinguishable from omitting. Sending an empty string `""` is the practical "clear" gesture.

**`void replacePermissions(UUID planId, Collection<String> permissionCodes)`** — the heart of the **P0-2 fix**.

```java
if (permissionCodes == null)
    throw ApiException.badRequest("permissionCodes is required (send an empty array to clear all permissions)");
planRepo.findById(planId).orElseThrow(() -> ApiException.notFound("Plan not found"));
permRepo.deleteByIdPlanId(planId);
permRepo.flush();                              // force DELETE before re-INSERT
Set<String> uniq = new TreeSet<>(permissionCodes);   // dedupe + deterministic order
for (String code : uniq) {
    if (code == null || code.isBlank()) continue;     // drop empties
    rows.add(PlanPermission.builder()
        .id(PlanPermission.Pk.builder().planId(planId).permissionCode(code.trim()).build())
        .build());
}
permRepo.saveAll(rows);
```

Key behaviors and the WHY behind each:
- **Null guard = defense in depth.** The controller already rejects a null/absent list via `@NotNull`, but the service re-checks because it's also called internally from `createPlan` and from tests. A null list must *never* be silently interpreted as "delete everything" — that was the original P0-2 data-loss bug, where a field-name mismatch deserialized to `null` and wiped a plan's permissions. An **explicit empty collection is allowed** and means "clear all."
- **`deleteByIdPlanId` + `flush()` then `saveAll`.** Replace = wipe-and-reinsert. The explicit `flush()` is critical: without it, Hibernate could reorder the `INSERT`s before the `DELETE` and trip the composite-PK unique constraint when a code that already existed is re-added in the same transaction. Flushing the delete first guarantees the rows are gone before reinserts.
- **`TreeSet` dedupe.** Incoming codes are de-duplicated and sorted, so `["api.v1","api.v1","api.v2"]` becomes two rows in a stable order — avoids PK collisions and makes output deterministic.
- **`code.trim()`** on insert and blank-skip — tolerant of sloppy whitespace from the UI.
- Audit: `plan.permissions.replaced`.

**`void replaceFeatures(UUID planId, Map<String,Object> features)`** — the feature analogue.

```java
planRepo.findById(planId).orElseThrow(...notFound...);
featureRepo.deleteByIdPlanId(planId);
featureRepo.flush();
if (features == null) return;          // null tolerated here (see note)
for (entry e : features.entrySet()) {
    if (key blank) continue;
    try { json = objectMapper.writeValueAsString(e.getValue()); }
    catch (JsonProcessingException) { throw ApiException.badRequest("Invalid feature value for " + key); }
    rows.add(PlanFeature(planId, key, json));
}
featureRepo.saveAll(rows);
```

- Each feature value is serialized to a JSON string and stored in the `jsonb` column. A value Jackson can't serialize yields a clean `400`.
- **Asymmetry vs. permissions:** here a `null` features map is tolerated (treated as "clear all and stop"), whereas `replacePermissions` *throws* on null. WHY the difference: the features endpoint (`POST /plans/{id}/features`) and its request record `ReplaceFeaturesRequest` are **not `@NotNull`-validated**, so the service must defensively handle null without 500-ing. The semantic chosen — null ⇒ delete-then-return-empty — matches the wipe-and-replace contract, but note it does mean a null body clears features (this is intentional for features; it is exactly what was *not* allowed for permissions because permissions were the data-loss target).
- Audit: `plan.features.replaced`.

#### Read methods (all `@Transactional(readOnly = true)`)

| Method | Returns / behavior |
|--------|--------------------|
| `List<Plan> listPlans(boolean onlyActive)` | Unpaged: `findAllByActiveTrue()` or `findAll()`. |
| `List<Plan> listPlans(boolean onlyActive, Pageable)` | Paged variant; returns `.getContent()` of the page (drops paging metadata — the API returns a bare array, not a `Page` envelope). |
| `Plan get(UUID id)` | `findById` or `ApiException.notFound`. The internal "load or 404" used by the write methods and detail assembly. |
| `Plan getByCode(String code)` | `findByCode` or `notFound("Plan not found: "+code)`. Used by code-keyed callers. |
| `List<String> getPermissions(UUID planId)` | Maps rows → `permissionCode`, **sorted**. Feeds the entitlement baseline in `LicenseClaimsBuilder`. |
| `Map<String,Object> getFeatures(UUID planId)` | Builds a `LinkedHashMap` (preserves insertion/iteration order) parsing each `value_json` back via `objectMapper.readValue(json, Object.class)`. **On parse failure it falls back to the raw JSON string** rather than throwing — a defensive read so one corrupt feature row can't break license issuance. Consumed by `LicenseClaimsBuilder` and `ActivationService` (`seats`). |
| `PlanDto getPlanWithDetails(UUID id)` | `get(id)` + `getPermissions` + `getFeatures` → full DTO. The standard "return the plan" path for the controller (every write returns this). |
| `List<PlanDto> listWithDetails(boolean onlyActive)` / `(…, Pageable)` | List + per-plan detail via `toDtos`. |
| *private* `List<PlanDto> toDtos(List<Plan>)` | Per-plan fan-out to `getPermissions`/`getFeatures` (the N+1 noted above). |

**Transaction & concurrency notes**

- The replace methods run inside a single transaction: delete + flush + reinsert are atomic. A failure mid-way rolls back the delete, so a plan never ends up with *no* permissions due to a partial reinsert.
- `getFeatures`'s try/catch-and-fallback is a deliberate **fail-open read**: license minting must not blow up because one feature row contains unexpected JSON.
- `AuditContext.set/setTarget` only stash breadcrumbs on a thread-local; the actual audit row is written downstream by the audit interceptor for the request. The service does not itself persist audit records.

---

### `PlanController.java`

**Responsibility:** REST surface at `/api/v1/plans`. Thin: it binds + validates request bodies, enforces method-level authorization, delegates to `PlanService`, and (for writes) re-reads the full detail DTO so the client always gets the post-write state. It also **declares the request DTO records** (`CreatePlanRequest`, `UpdatePlanRequest`, `ReplacePermissionsRequest`, `ReplaceFeaturesRequest`) as nested records.

**Endpoints**

| Method + path | Auth (`@PreAuthorize`) | Body | Delegates to | Returns |
|---|---|---|---|---|
| `GET /api/v1/plans?activeOnly&page&size` | `isAuthenticated()` | — | `listWithDetails(activeOnly, pageable)` | `List<PlanDto>` |
| `GET /api/v1/plans/{id}` | `isAuthenticated()` | — | `getPlanWithDetails(id)` | `PlanDto` |
| `POST /api/v1/plans` | `hasAuthority('plan.write')` | `CreatePlanRequest` | `createPlan(...)` then `getPlanWithDetails` | `201` + `PlanDto` |
| `PATCH /api/v1/plans/{id}` | `hasAuthority('plan.write')` | `UpdatePlanRequest` | `updatePlan(...)` then re-read | `PlanDto` |
| `POST /api/v1/plans/{id}/permissions` | `hasAuthority('plan.write')` | `ReplacePermissionsRequest` | `replacePermissions(id, codes)` then re-read | `PlanDto` |
| `POST /api/v1/plans/{id}/features` | `hasAuthority('plan.write')` | `ReplaceFeaturesRequest` | `replaceFeatures(id, features)` then re-read | `PlanDto` |

**Authorization model — read vs. write split.** Reads only require an authenticated principal (`isAuthenticated()`) so any logged-in admin user can browse the catalog. **All mutations require the fine-grained `plan.write` authority.** This is a deliberate least-privilege gate: only operators granted `plan.write` (via RBAC) can edit what every customer's license is allowed to do. Authorization is method-level via Spring Security `@PreAuthorize` SpEL, evaluated before the body is processed.

**Pagination (P3 fix) — `GET /plans`.** The list method accepts optional `page`/`size` and builds a `Pageable` via `PageRequestParams.of(page, size, null)`. That helper **caps `size` at `MAX_SIZE = 200`** and defaults to `DEFAULT_SIZE = 20`, so the catalog endpoint can **never return an unbounded result set** (the original P3 finding). The comment in the code spells this out. Sorting is fixed to unsorted here (the third arg is `null`). The default window covers the small seeded catalog; large catalogs must page explicitly.

#### Request records

**`CreatePlanRequest`**
```java
record CreatePlanRequest(
    @NotBlank @Size(max = 64) String code,   // required, ≤64
    @Size(max = 255) String name,
    String description,                        // unbounded text
    @Size(max = 32) String tier,
    Integer defaultTtlDays,                    // nullable → service defaults to 365
    Boolean active,                            // nullable → service defaults to true
    List<String> permissions,                  // nullable → leave empty on create
    Map<String,Object> features                // nullable → leave empty on create
) {}
```
Boxed `Integer`/`Boolean` and nullable collections are intentional: they let `PlanService` distinguish "field absent" from "explicitly set," which drives the create-time defaults. `code`'s `@Size(max=64)` matches the `plans.code VARCHAR(64)` column.

**`UpdatePlanRequest`** — `name`, `description`, `tier`, `defaultTtlDays`, `active`; **no `code`** (the business key is immutable via the API) and **no permissions/features** (those have dedicated endpoints). All nullable to support PATCH "skip if null" semantics.

**`ReplacePermissionsRequest` — the headline contract (P0-2).**
```java
public record ReplacePermissionsRequest(
    @NotNull(message = "permissionCodes is required (send an empty array to clear all permissions)")
    @JsonAlias("permissions")
    List<@NotBlank @Size(max = 64) String> permissionCodes) {}
```
This tiny record encodes three security decisions:
1. **`@NotNull` on the list** — an absent or `null` `permissionCodes` is rejected with `400`, *not* treated as "delete everything." This is the fix for the P0-2 data-loss bug where a missing/mis-named field bound to `null` and silently wiped a plan's permissions. The bespoke message explicitly tells the client how to clear: send `[]`.
2. **`@JsonAlias("permissions")`** — accepts the admin UI's historical field name `permissions` in addition to the canonical `permissionCodes`. WHY: the original bug was precisely a **field-name mismatch** (client sent `permissions`, server expected another name → bound `null` → wipe). The alias makes a name mismatch impossible to silently null-bind; both names map to the same field.
3. **`@NotBlank @Size(max=64)` on the elements** — each code must be non-blank and ≤64 chars (the bean-validation cascades into list elements). Blank elements would otherwise be dropped silently by the service; here they're rejected at the edge.

An **explicit empty array `[]` is still valid** and is the sanctioned "clear all permissions" gesture — distinguished from null by `@NotNull` passing (empty list is non-null). The service re-enforces the same null rule as defense in depth.

**`ReplaceFeaturesRequest`** — `record ReplaceFeaturesRequest(Map<String,Object> features)`. **Notably unvalidated** (no `@NotNull`, and the controller method does *not* annotate the body `@Valid`). Consequences: a `null`/absent `features` is accepted and reaches the service, which treats null as "clear all features." This is the documented asymmetry with permissions — features were not the data-loss target, and `replaceFeatures` is null-tolerant by design. A new engineer should not "fix" this by copy-pasting the `@NotNull` from the permissions record without understanding the intentional difference.

**Why writes re-read the DTO.** Every mutating endpoint calls `planService.getPlanWithDetails(id)` *after* the write and returns that. This guarantees the response reflects the persisted, normalized, sorted state (e.g. deduped/sorted permissions, parsed feature values) rather than echoing the raw request — so the client's view never drifts from the database.

---

## Cross-cutting gotchas summary

- **Two intentional null-handling asymmetries.** `replacePermissions` rejects null (P0-2 data-loss guard, enforced at both controller `@NotNull` and service). `replaceFeatures` tolerates null (clears features). Don't unify them.
- **Replace = delete + `flush()` + reinsert** in one transaction; the `flush()` is load-bearing (prevents reinsert-before-delete PK collisions). Applies to both permissions and features.
- **No `@Version` on `Plan`** ⇒ header edits are last-write-wins under concurrency. Child tables are protected by the atomic replace.
- **Feature values are JSON, stored as serialized strings** in `jsonb`; reads parse back to `Object` with a raw-string fallback so license issuance can't be broken by one bad row.
- **Listing is N+1** (2 queries per plan) but bounded by mandatory pagination (`MAX_SIZE=200`). Optimize with batch/join-fetch if the catalog grows.
- **Length constraints differ between layers:** API caps codes at 64 (`@Size`), DB allows 128 (`permission_code VARCHAR(128)`); `feature_key` is DB-capped at 64 with no edge validation.
- **No delete endpoint** — deactivate via `active=false`. The DB FKs cascade on plan delete, but the API never deletes plans.
- **Editing a plan is forward-only:** it changes future license issuance for all subscriptions on that plan; already-signed `.lic` tokens are immutable snapshots.
