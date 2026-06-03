package com.example.cp.usage;

import com.example.cp.common.Ids;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usage-integrity integration coverage built on {@link AbstractIntegrationTest}.
 *
 * <p>Exercises the four hardening invariants on {@code POST /api/v1/usage/ingest} contracted in
 * {@code docs/design/usage-integrity.md} / {@code CONTRACT.md} (bucket D):
 *
 * <ol>
 *   <li><b>Cross-tenant ingest is blocked.</b> A machine (API-key) caller bound to org A presents a
 *       {@code jti} that resolves (via {@code license_tokens -> subscriptions}) to org B's
 *       subscription. Ingest binds to the caller org and rejects the foreign license
 *       ({@code 403}, or {@code 404} for non-disclosure) and writes ZERO rows.</li>
 *   <li><b>Quantity validation.</b> {@code quantity = 0} and {@code quantity = -5} (and a missing
 *       quantity) are rejected with {@code 400}; nothing is persisted. A valid fractional quantity
 *       is accepted ({@code 202}).</li>
 *   <li><b>Idempotency / dedup.</b> The same {@code (subscriptionId, jti, eventId)} sent twice
 *       across two requests persists exactly ONE row and counts the quota only once (no
 *       double-count). Two events sharing an {@code eventId} within a single batch collapse to one.
 *       Distinct {@code eventId}s both persist.</li>
 *   <li><b>Over-limit rejection.</b> With {@code app.usage.enforce-limit=true} (the default), an
 *       ingest that would push {@code consumed_value} over a non-null {@code limit_value} is rejected
 *       with {@code 409} and the stored {@code consumed_value} is left unchanged (the whole
 *       transaction rolls back); an ingest that fits succeeds and accumulates.</li>
 * </ol>
 *
 * <p>The caller is modelled as an API key via {@link #asApiKey(UUID, String...)} (the authoritative
 * machine-ingest path: an {@code ApiKeyAuthentication} principal carries {@code apiKeyOrgId}, which
 * the service/{@code @tenantAccess} use to bind ingest to the caller's org). The {@code usage.ingest}
 * scope mirrors the migration-13 permission catalog and the default API-key creatable-scopes.
 *
 * <p>Request bodies are assembled as plain maps and serialised with the shared {@code objectMapper}
 * so the test is decoupled from the controller's request-record shape (e.g. the optional per-event
 * {@code eventId} idempotency key). Persistence is asserted directly against the usage repositories
 * (autowired here; the base harness does not expose them) to prove "no double-count" /
 * "consumed_value unchanged" independently of any read endpoint's authorization.
 */
class UsageIngestIntegrityIT extends AbstractIntegrationTest {

    private static final String INGEST = "/api/v1/usage/ingest";

    @Autowired private LicenseTokenRepository licenseTokenRepository;
    @Autowired private UsageEventRepository usageEventRepository;
    @Autowired private UsageQuotaRepository usageQuotaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 1. Cross-tenant ingest is blocked (and writes nothing)
    // ------------------------------------------------------------------

    @Test
    void apiKeyForOrgA_cannotIngestForOrgBSubscription_andNothingIsPersisted() throws Exception {
        Plan plan = seedPlan("pro");

        Organization orgA = seedOrg("Org A");
        Organization orgB = seedOrg("Org B");

        // The target license belongs to ORG B.
        Subscription subB = seedSubscription(orgB.getId(), plan.getId());
        String jtiB = seedActiveLicense(subB.getId());

        // Caller is an API key bound to ORG A, holding the ingest scope.
        ResultActions res = mockMvc.perform(post(INGEST)
                .with(asApiKey(orgA.getId(), "usage.ingest"))
                .contentType("application/json")
                .content(ingestJson(jtiB, event("seats", BigDecimal.ONE, OffsetDateTime.now(), "evt-" + rnd()))));

        int statusCode = res.andReturn().getResponse().getStatus();
        // Cross-tenant access is denied: 403 (forbidden) or 404 (existence non-disclosure).
        assertThat(statusCode)
                .as("cross-tenant ingest must be rejected, was %s", statusCode)
                .isIn(403, 404);

        // Zero rows written for org B's subscription.
        assertThat(usageEventRepository.findInRange(subB.getId(), null, null)).isEmpty();
        assertThat(usageQuotaRepository.findBySubscriptionId(subB.getId())).isEmpty();
    }

    @Test
    void apiKeyForCorrectOrg_canIngest() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Own");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("3"), OffsetDateTime.now(), "evt-" + rnd()))))
                .andExpect(status().isAccepted());

        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 2. Quantity validation
    // ------------------------------------------------------------------

    @Test
    void zeroQuantity_isRejected_400_andNothingPersisted() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Qty Zero");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", BigDecimal.ZERO, OffsetDateTime.now(), "evt-" + rnd()))))
                .andExpect(status().isBadRequest());

        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).isEmpty();
    }

    @Test
    void negativeQuantity_isRejected_400_andNothingPersisted() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Qty Neg");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("-5"), OffsetDateTime.now(), "evt-" + rnd()))))
                .andExpect(status().isBadRequest());

        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).isEmpty();
    }

    @Test
    void validFractionalQuantity_isAccepted_202() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Qty Ok");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("api_calls", new BigDecimal("2.5"), OffsetDateTime.now(), "evt-" + rnd()))))
                .andExpect(status().isAccepted());

        List<UsageEvent> events = usageEventRepository.findInRange(sub.getId(), null, null);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getQuantity()).isEqualByComparingTo("2.5");
    }

    // ------------------------------------------------------------------
    // 3. Idempotency / dedup on (subscriptionId, jti, eventId)
    // ------------------------------------------------------------------

    @Test
    void duplicateEventId_acrossTwoRequests_isIdempotent_noDoubleCount() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Idem");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        OffsetDateTime occurred = OffsetDateTime.now();
        String eventId = "evt-" + rnd();
        BigDecimal qty = new BigDecimal("4");

        // First ingest: accepted, one row, consumed = 4.
        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", qty, occurred, eventId))))
                .andExpect(status().isAccepted());

        // Replay of the SAME (subscriptionId, jti, eventId): de-duplicated, NOT counted again.
        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", qty, occurred, eventId))))
                .andExpect(status().isAccepted());

        // Exactly one persisted row and the quota counted the quantity once.
        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).hasSize(1);
        assertThat(consumed(sub.getId(), "seats", occurred)).isEqualByComparingTo(qty);
    }

    @Test
    void duplicateEventId_withinOneBatch_collapsesToSingleRow() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Idem Batch");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        OffsetDateTime occurred = OffsetDateTime.now();
        String sharedId = "evt-" + rnd();
        BigDecimal qty = new BigDecimal("2");

        // Two events with the SAME eventId in one batch must not trip the dedup unique index nor
        // double-count; they collapse to a single persisted row.
        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti,
                                event("seats", qty, occurred, sharedId),
                                event("seats", qty, occurred, sharedId))))
                .andExpect(status().isAccepted());

        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).hasSize(1);
        assertThat(consumed(sub.getId(), "seats", occurred)).isEqualByComparingTo(qty);
    }

    @Test
    void distinctEventIds_bothPersist_andAccumulate() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Distinct");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        OffsetDateTime occurred = OffsetDateTime.now();

        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti,
                                event("seats", new BigDecimal("3"), occurred, "evt-" + rnd()),
                                event("seats", new BigDecimal("5"), occurred, "evt-" + rnd()))))
                .andExpect(status().isAccepted());

        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).hasSize(2);
        assertThat(consumed(sub.getId(), "seats", occurred)).isEqualByComparingTo(new BigDecimal("8"));
    }

    // ------------------------------------------------------------------
    // 4. Over-limit ingest rejected when enforce-limit (default true)
    // ------------------------------------------------------------------

    @Test
    void overLimitIngest_isRejected_409_andConsumedUnchanged() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Limit");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        OffsetDateTime occurred = OffsetDateTime.now();
        // Seed a quota row with limit 10 and consumed 8 for this feature/period.
        seedQuota(sub.getId(), "seats", occurred, new BigDecimal("10"), new BigDecimal("8"));

        // Ingesting 5 would push consumed to 13 > 10 -> rejected, transaction rolled back.
        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("5"), occurred, "evt-" + rnd()))))
                .andExpect(status().isConflict());

        // No event row written and the consumed value is unchanged at 8.
        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).isEmpty();
        assertThat(consumed(sub.getId(), "seats", occurred)).isEqualByComparingTo(new BigDecimal("8"));
    }

    @Test
    void withinLimitIngest_isAccepted_andAccumulatesUpToLimit() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Org Limit Ok");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        OffsetDateTime occurred = OffsetDateTime.now();
        seedQuota(sub.getId(), "seats", occurred, new BigDecimal("10"), new BigDecimal("8"));

        // Ingesting 2 brings consumed to exactly the limit (10) -> accepted.
        mockMvc.perform(post(INGEST)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("2"), occurred, "evt-" + rnd()))))
                .andExpect(status().isAccepted());

        assertThat(usageEventRepository.findInRange(sub.getId(), null, null)).hasSize(1);
        assertThat(consumed(sub.getId(), "seats", occurred)).isEqualByComparingTo(new BigDecimal("10"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Seeds an ACTIVE {@link LicenseToken} for {@code subscriptionId} and returns its jti. */
    private String seedActiveLicense(UUID subscriptionId) {
        String jti = "jti-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        LicenseToken t = LicenseToken.builder()
                .id(Ids.newId())
                .jti(jti)
                .subscriptionId(subscriptionId)
                .kid("test-kid")
                .issuedAt(now)
                .expiresAt(now.plusYears(1))
                .status(LicenseToken.Status.ACTIVE)
                .build();
        licenseTokenRepository.save(t);
        return jti;
    }

    /**
     * Seeds a {@code usage_quotas} row directly (the upsert SQL only sets {@code limit_value} on
     * INSERT, so a test that needs a non-null limit must pre-create the row). Uses the same UTC
     * month-start period bucket the service computes for {@code occurredAt}.
     */
    private void seedQuota(UUID subscriptionId, String featureKey, OffsetDateTime occurredAt,
                           BigDecimal limit, BigDecimal consumed) {
        OffsetDateTime periodStart = monthStartUtc(occurredAt);
        OffsetDateTime periodEnd = periodStart.plusMonths(1);
        jdbcTemplate.update("""
                INSERT INTO usage_quotas (subscription_id, feature_key, period_start, period_end, limit_value, consumed_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """, subscriptionId, featureKey, periodStart, periodEnd, limit, consumed);
    }

    /** Current {@code consumed_value} for a (sub, feature, period) bucket, or zero if absent. */
    private BigDecimal consumed(UUID subscriptionId, String featureKey, OffsetDateTime occurredAt) {
        OffsetDateTime periodStart = monthStartUtc(occurredAt);
        List<BigDecimal> rows = jdbcTemplate.queryForList("""
                SELECT consumed_value FROM usage_quotas
                WHERE subscription_id = ? AND feature_key = ? AND period_start = ?
                """, BigDecimal.class, subscriptionId, featureKey, periodStart);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private static OffsetDateTime monthStartUtc(OffsetDateTime t) {
        OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    }

    /** A single usage event as a JSON-serialisable map (camelCase field names). */
    private Map<String, Object> event(String featureKey, BigDecimal quantity, OffsetDateTime occurredAt, String eventId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", eventId);
        e.put("featureKey", featureKey);
        e.put("quantity", quantity);
        e.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
        return e;
    }

    @SafeVarargs
    private String ingestJson(String jti, Map<String, Object>... events) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jti", jti);
        body.put("events", new ArrayList<>(List.of(events)));
        return objectMapper.writeValueAsString(body);
    }
}
