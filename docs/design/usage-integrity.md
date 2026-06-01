# Design spec: usage-integrity

## Shared contracts (other files depend on these — keep signatures exact)

### `app.usage.occurred-at-max-past` (config-property)
- **Purpose:** Max age of occurredAt accepted by ingest (reject events older than this). Default P35D so a month-period upsert stays meaningful.
- **Signature/contract:**

```
app.usage.occurred-at-max-past: java.time.Duration (default PT840H / P35D)
```

### `app.usage.occurred-at-max-future` (config-property)
- **Purpose:** Max clock-skew into the future accepted for occurredAt.
- **Signature/contract:**

```
app.usage.occurred-at-max-future: java.time.Duration (default PT5M)
```

### `app.usage.enforce-limit` (config-property)
- **Purpose:** Feature flag: when true, ingest rejects events that would push consumed_value over a non-null limit_value (HTTP 409).
- **Signature/contract:**

```
app.usage.enforce-limit: boolean (default true)
```

### `usageAccess (com.example.cp.usage.UsageAccess)` (bean)
- **Purpose:** @PreAuthorize SpEL helper binding ingest to caller org. Mirrors existing @subAccess pattern (SubscriptionAccess).
- **Signature/contract:**

```
@Component("usageAccess") class UsageAccess { boolean canIngestForSubscription(java.util.UUID subscriptionId); java.util.UUID currentCallerOrgId(); }
```

### `CallerOrg resolution helper on SecurityUtils` (interface)
- **Purpose:** Expose the API-key/user caller org so the service can bind ingest to it; today orgId is trapped inside ApiKeyAuthFilter.ApiKeyAuthentication and unreachable.
- **Signature/contract:**

```
static java.util.Optional<java.util.UUID> SecurityUtils.currentOrgId()
```

### `UsageIngestService.IngestEvent (modified)` (record)
- **Purpose:** Carry the new per-event idempotency key eventId from controller to service.
- **Signature/contract:**

```
record IngestEvent(String eventId, String featureKey, BigDecimal quantity, OffsetDateTime occurredAt, Map<String,Object> metadata)
```

### `13-usage-integrity.sql` (db-migration)
- **Purpose:** Add event_id + dedup unique index + quantity CHECK to usage_events, consumed_value/limit_value CHECKs to usage_quotas, and seed usage.read/usage.write permissions with role grants.
- **Signature/contract:**

```
changesets: cp:13-usage-events-event-id, cp:13-usage-events-dedup-uidx, cp:13-usage-events-qty-check, cp:13-usage-quotas-checks, cp:13-usage-permissions-seed
```

## File edits

### [NEW FILE] `control-panel-api/src/main/resources/db/changelog/changes/13-usage-integrity.sql`
- depends on: 13-usage-integrity.sql
- NEW Liquibase formatted-sql file (header: `--liquibase formatted sql`). Place AFTER 12-additional-permissions.sql conventions (VARCHAR/UUID, ON CONFLICT DO NOTHING seeds, --rollback per changeset).
- changeset `cp:13-usage-events-event-id`: `ALTER TABLE usage_events ADD COLUMN event_id VARCHAR(128);` (nullable; client-supplied idempotency key, optional). --rollback `ALTER TABLE usage_events DROP COLUMN event_id;`
- changeset `cp:13-usage-events-dedup-uidx`: create a PARTIAL unique index for dedup only when event_id is present: `CREATE UNIQUE INDEX uidx_usage_events_dedup ON usage_events(subscription_id, jti, event_id) WHERE event_id IS NOT NULL;`. Rationale: matches the (subscription_id, jti, event_id) dedup key in the task; partial so legacy/no-event_id rows are unaffected. --rollback `DROP INDEX uidx_usage_events_dedup;`
- changeset `cp:13-usage-events-qty-check`: `ALTER TABLE usage_events ADD CONSTRAINT chk_usage_events_qty_positive CHECK (quantity > 0);` (DB backstop for quantity>0; #21). --rollback drop constraint.
- changeset `cp:13-usage-quotas-checks`: `ALTER TABLE usage_quotas ADD CONSTRAINT chk_usage_quotas_consumed_nonneg CHECK (consumed_value >= 0);` and `ADD CONSTRAINT chk_usage_quotas_limit_nonneg CHECK (limit_value IS NULL OR limit_value >= 0);` (#22/#52). --rollback drop both.
- changeset `cp:13-usage-permissions-seed`: INSERT INTO permissions(code,name,description,category) VALUES ('usage.read','Read usage','View usage events and quotas','usage'),('usage.write','Write usage','Ingest usage events','usage') ON CONFLICT (code) DO NOTHING; then grant usage.read+usage.write to SUPER_ADMIN, ORG_OWNER, ORG_ADMIN and usage.read to ORG_MEMBER/VIEWER via the same `INSERT INTO role_permissions SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN (...) WHERE r.code='...' ON CONFLICT DO NOTHING` pattern used in 02-rbac.sql / 12-additional-permissions.sql. Critical: `usage.read` is referenced by @PreAuthorize on listUsage but is NOT seeded anywhere today, and `usage.write` does not exist yet. --rollback delete the two permissions and their role_permissions rows.

### [MODIFY] `control-panel-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Append a new include entry AFTER the 12-additional-permissions.sql include and before/after 99-auth-password-reset.sql (numeric order; place it before the 99- entry): `  - include:\n      file: db/changelog/changes/13-usage-integrity.sql`.
- Risk: ordering matters only relative to 02/12 (which create permissions/roles) — 13 depends on `permissions`, `roles`, `role_permissions`, `usage_events`, `usage_quotas` all existing, so it must come after 02, 07, and 12.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/usage/UsageEvent.java`
- Add field for the idempotency key: `@Column(name = "event_id", length = 128) private String eventId;` (place near the `jti` field). Lombok @Builder/@Getter/@Setter auto-cover it.
- No nullable=false (event_id is optional per migration).

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestController.java`
- depends on: usageAccess (com.example.cp.usage.UsageAccess)
- Replace the unauthenticated/unauthorized ingest guard. Remove the bare `SecurityUtils.requireUser();` body line and add a class/method authorization annotation: `@PreAuthorize("hasAuthority('usage.write')")` on the ingest() method (machine clients via API key get scope usage.write; super admins pass via AuthenticatedUser.hasAuthority short-circuit).
- Bind ingest to caller org: pass the caller-derived orgId into the service rather than trusting jti alone. Two acceptable shapes — pick (a) for least churn: (a) keep controller thin; call `service.ingest(body.jti(), events)` but have the service enforce org binding via UsageAccess (see service edits). (b) resolve org in controller via new `SecurityUtils.currentOrgId()` and pass it to an overloaded `service.ingest(callerOrgId, jti, events)`. Spec mandates the orgId be threaded to the service for the subscription-org check.
- Extend the inbound EventDto record (lines 72-77) with the optional idempotency key and tighten quantity validation: add `@jakarta.validation.constraints.DecimalMin(value="0", inclusive=false) BigDecimal quantity` (rejects null? no — DecimalMin allows null; ALSO add `@NotNull` so quantity is required and positive) and add a new component `String eventId` (no annotation, optional, max length enforced by `@Size(max=128)`). New record: `record EventDto(@NotBlank String featureKey, @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quantity, OffsetDateTime occurredAt, @Size(max=128) String eventId, Map<String,Object> metadata)`.
- Update the mapping lambda (lines 39-45) to pass `e.eventId()` into the new `UsageIngestService.IngestEvent(e.eventId(), e.featureKey(), e.quantity(), e.occurredAt(), e.metadata())` constructor.
- Optionally surface eventId in the UsageReport.EventDto projection (lines 82-86) for read-back; add `String eventId` and map `e.getEventId()`. Non-load-bearing but recommended for verification tests.
- Imports to add: `jakarta.validation.constraints.NotNull`, `jakarta.validation.constraints.DecimalMin`, `jakarta.validation.constraints.Size`. SecurityUtils import already present; remove its use if controller no longer calls requireUser().

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/usage/UsageIngestService.java`
- depends on: app.usage.occurred-at-max-past, app.usage.occurred-at-max-future, app.usage.enforce-limit, UsageIngestService.IngestEvent (modified), usageAccess (com.example.cp.usage.UsageAccess)
- Modify IngestEvent record (line 39) to add eventId: `record IngestEvent(String eventId, String featureKey, BigDecimal quantity, OffsetDateTime occurredAt, Map<String,Object> metadata) {}`.
- Inject config: add constructor params / @Value fields `@Value("${app.usage.occurred-at-max-past:P35D}") Duration maxPast`, `@Value("${app.usage.occurred-at-max-future:PT5M}") Duration maxFuture`, `@Value("${app.usage.enforce-limit:true}") boolean enforceLimit`. Add a `Clock` field (default Clock.systemUTC()) to make OffsetDateTime.now() testable; replace OffsetDateTime.now() calls with OffsetDateTime.now(clock).
- Org binding (#20): change signature to `ingest(UUID callerOrgId, String jti, List<IngestEvent> events)` (or resolve callerOrgId inside via UsageAccess.currentCallerOrgId()). After resolving token (lines 51-55) and computing subId (line 57), load the subscription via SubscriptionRepository.findById(subId) and assert `subscription.getOrgId().equals(callerOrgId)`; if not, throw `ApiException.forbidden("License does not belong to caller organization")`. Add SubscriptionRepository as a constructor dependency. Super-admin bypass: if caller has authority via UsageAccess/SecurityUtils superAdmin, skip the org-equality check.
- Per-event quantity validation (#21): after defaulting, REMOVE the silent `qty = null ? ONE` fallback OR keep a fallback only when null is still permitted; since controller now enforces @NotNull+@DecimalMin, add a defensive service check `if (e.quantity() == null || e.quantity().signum() <= 0) throw ApiException.badRequest("quantity must be > 0");` (covers direct service callers/tests bypassing bean validation).
- occurredAt window validation (#76): compute `OffsetDateTime now = OffsetDateTime.now(clock);` default occurred=now when null; then `if (occurred.isAfter(now.plus(maxFuture))) throw ApiException.badRequest("occurredAt is too far in the future");` and `if (occurred.isBefore(now.minus(maxPast))) throw ApiException.badRequest("occurredAt is too far in the past");`.
- Idempotency/dedup (#40): before building UsageEvent, when eventId is non-blank, call new repo method `eventRepo.existsBySubscriptionIdAndJtiAndEventId(subId, jti, eventId)`; if true, SKIP this event (do not persist, do not upsert quota) and count it as a deduped/no-op rather than throwing. Track acceptedCount vs dedupedCount. Also de-dupe within the same batch (a HashSet of eventIds seen this call) to avoid the unique-index violation inside one transaction. Set ue.eventId(eventId) on the builder. Rely on the partial unique index as the race backstop: wrap saveAll in handling that catches `org.springframework.dao.DataIntegrityViolationException` and re-maps to `ApiException.conflict("Duplicate usage event")` (or, preferably, persist per-event with try/catch so a late duplicate is treated as deduped).
- limit_value enforcement (#52): in upsertQuota, change the blind add to a guarded upsert. Before/while upserting, when enforceLimit is true and the existing/limit_value is non-null, ensure consumed_value + qty <= limit_value; if it would exceed, throw `ApiException.conflict("Usage limit exceeded for feature "+featureKey)` and let the @Transactional rollback the whole batch. Implementation options: (a) do a `SELECT ... FOR UPDATE` on the quota row first then compute, or (b) keep the single SQL upsert but add a guard clause that fails the statement when exceeding — e.g. add `WHERE` predicate is not possible on ON CONFLICT DO UPDATE, so prefer reading the row with quotaRepo + pessimistic lock, computing the new total in Java, throwing on breach, then performing the existing jdbc upsert. Note current upsert sets limit_value only on INSERT (NULL); preserve that — do not overwrite an admin-set limit_value on conflict (current SQL already leaves limit_value untouched on UPDATE — keep that).
- Return shape: extend IngestResult to also report dedup count if controller/audit needs it: `record IngestResult(int eventsAccepted, int eventsDeduped, UUID subscriptionId)`. Update AuditContext payload in controller accordingly (events_count + deduped_count).
- Imports to add: java.time.Duration, java.time.Clock, java.util.HashSet/Set, com.example.cp.subscriptions.SubscriptionRepository, org.springframework.beans.factory.annotation.Value, org.springframework.dao.DataIntegrityViolationException.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/usage/UsageEventRepository.java`
- Add dedup existence query used by the service: `boolean existsBySubscriptionIdAndJtiAndEventId(UUID subscriptionId, String jti, String eventId);` (Spring Data derives it; matches new entity fields). No other change needed; findInRange unaffected.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/usage/UsageQuotaRepository.java`
- Add a pessimistic-lock finder for limit enforcement: import `org.springframework.data.jpa.repository.Lock` and `jakarta.persistence.LockModeType`; add `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT q FROM UsageQuota q WHERE q.subscriptionId=:s AND q.featureKey=:f AND q.periodStart=:p") Optional<UsageQuota> findForUpdate(@Param("s") UUID subscriptionId, @Param("f") String featureKey, @Param("p") OffsetDateTime periodStart);`. Used by service to read current consumed/limit before the jdbc upsert to enforce limit_value atomically within the transaction. (If team prefers SQL-only, this method can be omitted in favor of a guarded SELECT via JdbcTemplate inside the service.)

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/common/SecurityUtils.java`
- Add caller-org resolution so the service/UsageAccess can bind ingest to org without reaching into ApiKeyAuthFilter internals. New static method: `public static Optional<UUID> currentOrgId()` that reads `SecurityContextHolder.getContext().getAuthentication()`; if it is `com.example.cp.apikeys.ApiKeyAuthFilter.ApiKeyAuthentication`, return Optional.of(((ApiKeyAuthentication)auth).getOrgId()); otherwise Optional.empty() (interactive user sessions are not org-scoped here). Import ApiKeyAuthFilter.
- Risk: this introduces a dependency from common -> apikeys package. If that import direction is undesirable, instead put currentOrgId() inside UsageAccess (apikeys is already importable from usage) and skip editing SecurityUtils. Prefer the UsageAccess location to keep common/ free of feature deps.

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/usage/UsageAccess.java`
- depends on: usageAccess (com.example.cp.usage.UsageAccess)
- NEW @Component("usageAccess") class modeled on SubscriptionAccess (subscriptions/SubscriptionAccess.java). Dependencies: SubscriptionRepository subRepo, OrgMemberRepository memberRepo.
- Method `public UUID currentCallerOrgId()`: returns the API-key org from ApiKeyAuthFilter.ApiKeyAuthentication.getOrgId() when present, else null (interactive users). This is the authoritative caller-org used by the service for org binding.
- Method `public boolean canIngestForSubscription(UUID subscriptionId)`: SecurityUtils.currentUser().map(u -> u.superAdmin() || (u.hasAuthority("usage.write") && subRepo.findById(subscriptionId).map(s -> orgMatchesCaller(s.getOrgId())).orElse(false))).orElse(false). orgMatchesCaller compares currentCallerOrgId() to the subscription org (and/or OrgMember membership for interactive users).
- Note: because ingest resolves the subscription from the body jti (not a path var), the org-binding check must run in the service AFTER jti->subscription resolution; UsageAccess is the shared helper the service calls. The @PreAuthorize on the controller only enforces hasAuthority('usage.write'); the org-equality enforcement lives in the service via UsageAccess.currentCallerOrgId().

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- Under the existing `app:` block add a `usage:` section: `usage:\n    occurred-at-max-past: ${APP_USAGE_MAX_PAST:P35D}\n    occurred-at-max-future: ${APP_USAGE_MAX_FUTURE:PT5M}\n    enforce-limit: ${APP_USAGE_ENFORCE_LIMIT:true}`. Mirror the existing env-var-with-default style used for app.signing/app.sso.

### [NEW FILE] `control-panel-api/src/test/java/com/example/cp/usage/UsageIngestServiceTest.java`
- NEW test (no tests exist in control-panel-api yet; Testcontainers postgresql + spring-security-test are already on the test classpath, application-test.yml exists). Cover the integrity invariants listed in testCases. Use a @SpringBootTest + @Testcontainers Postgres slice OR a JdbcTemplate-backed service test; assert ApiException status codes via the thrown exception (no need to go through MVC) plus a few @WebMvcTest/MockMvc cases for @PreAuthorize('usage.write').

## Tests to add

- Cross-org IDOR (#20): API-key caller scoped to Org A supplies a valid jti belonging to Org B's subscription -> expect 403 Forbidden, zero rows written to usage_events.
- AuthZ (#20): caller lacking usage.write authority/scope hits POST /api/v1/usage/ingest -> 403 (denied by @PreAuthorize); caller with usage.write but correct org -> 202.
- Quantity validation (#21): body with quantity=0 -> 400; quantity=-5 -> 400; quantity=null -> 400 (bean validation); valid quantity=2.5 -> 202. Plus DB CHECK: a direct INSERT of quantity<=0 violates chk_usage_events_qty_positive.
- Idempotency/dedup (#40): same (subscription_id, jti, event_id) sent twice across two requests -> second is deduped (eventsAccepted reflects skip, only one row persisted). Two events with the same event_id within one batch -> only one persisted, no unique-index violation. Different event_id (or null event_id) -> both persisted.
- Race/backstop (#40): concurrent duplicate inserts rely on partial unique index uidx_usage_events_dedup; DataIntegrityViolationException is mapped to 409 or treated as deduped (assert no duplicate row).
- occurredAt window (#76): occurredAt 1 hour in the future -> 400 (beyond max-future); occurredAt older than max-past (e.g. 60 days with default P35D) -> 400; occurredAt within window -> 202; occurredAt null -> defaults to now, 202.
- limit_value enforcement (#52): seed usage_quotas with limit_value=10, consumed_value=8; ingest quantity=5 for that feature/period -> 409 Conflict, consumed_value unchanged (transaction rolled back); ingest quantity=2 -> 202, consumed_value=10. With app.usage.enforce-limit=false the over-limit ingest succeeds.
- Quota period mapping: events with occurredAt in different UTC months upsert into distinct (subscription_id, feature_key, period_start) rows; same month accumulates consumed_value.
- Permission seeding (#regression): GET /api/v1/subscriptions/{id}/usage works for a user granted usage.read (proves usage.read permission now exists after migration 13), and for usage.write holder on ingest.

## Risks / cross-file notes

- JPA ddl-auto=validate (application.yml): the new UsageEvent.eventId column MUST be added by migration 13 before the entity declares it, or Hibernate schema validation fails at startup. Ensure 13-usage-integrity.sql is included in db.changelog-master.yaml and runs before app boot.
- Compile ordering: UsageIngestService gains constructor params (SubscriptionRepository, @Value fields, Clock). Any existing instantiation in tests or wiring must be updated; controller call sites must match the new ingest(...) signature (callerOrgId/eventId/IngestResult fields).
- IngestEvent and IngestResult are public records consumed by UsageIngestController; changing their component lists is a breaking change that requires the controller edit in the same change to keep the module compiling.
- Package dependency direction: putting currentOrgId() in common/SecurityUtils creates common -> apikeys coupling. Prefer locating caller-org resolution in usage/UsageAccess (usage may depend on apikeys) to avoid a cycle; do not add the SecurityUtils edit if it introduces an import cycle.
- Org binding only works for API-key callers (ApiKeyAuthentication carries orgId). Interactive user (JwtAuthFilter) principals have no orgId; decide policy: either restrict /usage/ingest to API-key auth (recommended for machine ingest) or, for user callers, derive org via OrgMember membership against the subscription's org. Document which path is authoritative so the 403 logic is deterministic.
- Partial unique index uidx_usage_events_dedup only dedups when event_id IS NOT NULL; events without event_id are NOT deduplicated (by design). Clients must send event_id to get idempotency — make this explicit in API docs; consider whether null event_id should be rejected for usage.write callers (stricter) vs allowed (current spec keeps it optional).
- limit_value enforcement via read-then-upsert needs the pessimistic lock (findForUpdate) inside the same @Transactional as the jdbc upsert to avoid TOCTOU over-counting under concurrency; if the SQL ON CONFLICT path is kept without the lock, two concurrent batches can both pass the limit check. The DB CHECK on consumed_value only guards >=0, not <=limit, so app-level enforcement is required.
- DataIntegrityViolationException from the dedup unique index (or quantity CHECK) currently falls through to GlobalExceptionHandler.handleGeneric -> 500. Add explicit handling in the service (catch and remap to 409/400) so callers get a clean status; otherwise a duplicate produces a 500.
- Migration 13 seeds usage.read/usage.write and grants them; existing deployments that already manually added these codes are protected by ON CONFLICT (code) DO NOTHING. The listUsage @PreAuthorize already references usage.read which was never seeded — until 13 runs, only subscription.read holders can read usage; verify no environment depended on the pre-migration (broken) behavior.
