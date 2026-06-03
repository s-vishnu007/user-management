package com.example.cp.licenses;

import com.example.cp.common.Ids;
import com.example.cp.events.OutboxEvent;
import com.example.cp.events.OutboxEventRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.example.cp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the Wave 2 LICENSING bucket: lease/heartbeat -> activation upsert ->
 * seat counting, trial issuance, and the lifecycle scheduler's ACTIVE -> EXPIRED transition plus the
 * {@code license.expiring} outbox warning.
 *
 * <p>The heartbeat is exercised as the authoritative machine path: an org-scoped API key carrying
 * {@code usage.ingest} (the same principal {@code ApiKeyAuthFilter} builds), so the
 * {@code @tenantAccess.canIngestUsageForJti} binding is enforced.
 */
class LicenseHeartbeatActivationIT extends AbstractIntegrationTest {

    @Autowired private LicenseTokenRepository tokenRepo;
    @Autowired private LicenseActivationRepository activationRepo;
    @Autowired private LicenseIssuer issuer;
    @Autowired private ActivationService activationService;
    @Autowired private LicenseLifecycleScheduler scheduler;
    @Autowired private OutboxEventRepository outboxRepo;
    @Autowired private SubscriptionRepository subscriptionRepository;

    // ------------------------------------------------------------------
    // 1. Heartbeat upserts an activation, refreshes last-seen, counts seats
    // ------------------------------------------------------------------

    @Test
    void heartbeat_upsertsActivation_refreshesLastSeen_andCountsSeats() throws Exception {
        Organization org = seedOrg("HB Org");
        Plan plan = seedPlan("pro");
        Subscription sub = seedSubscriptionWithSeats(org.getId(), plan.getId(), 10);
        String jti = issuer.issue(sub.getId(), 30, null).jti();

        // First beat from node-1 -> creates an activation, token last_seen set, 1 active seat.
        MvcResult r1 = heartbeat(org.getId(), jti, "node-1")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode b1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertThat(b1.get("activeSeats").asLong()).isEqualTo(1);
        assertThat(b1.get("overLimit").asBoolean()).isFalse();
        assertThat(b1.get("seatLimit").asInt()).isEqualTo(10);

        assertThat(activationRepo.findByJtiAndNodeId(jti, "node-1")).isPresent();
        LicenseActivation a1 = activationRepo.findByJtiAndNodeId(jti, "node-1").orElseThrow();
        OffsetDateTime firstSeen = a1.getFirstSeenAt();
        OffsetDateTime lastSeen1 = a1.getLastSeenAt();
        assertThat(tokenRepo.findByJti(jti).orElseThrow().getLastSeenAt()).isNotNull();

        // Second beat from the SAME node -> NO new row, last_seen advanced, still 1 active seat.
        heartbeat(org.getId(), jti, "node-1").andExpect(status().isOk());
        List<LicenseActivation> all = activationRepo.findByJtiOrderByLastSeenAtDesc(jti);
        assertThat(all).hasSize(1);
        LicenseActivation a1b = activationRepo.findByJtiAndNodeId(jti, "node-1").orElseThrow();
        assertThat(a1b.getFirstSeenAt()).isEqualTo(firstSeen);             // unchanged
        assertThat(a1b.getLastSeenAt()).isAfterOrEqualTo(lastSeen1);       // renewed

        // A DIFFERENT node -> a second activation, 2 active seats.
        MvcResult r2 = heartbeat(org.getId(), jti, "node-2")
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(r2.getResponse().getContentAsString())
                .get("activeSeats").asLong()).isEqualTo(2);
        assertThat(activationRepo.findByJtiOrderByLastSeenAtDesc(jti)).hasSize(2);
        assertThat(activationService.activeSeatCount(jti)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 2. Seat enforcement: a NEW node beyond the seat limit is rejected (409)
    // ------------------------------------------------------------------

    @Test
    void newNodeBeyondSeatLimit_isRejected409_andNotPersisted() throws Exception {
        Organization org = seedOrg("Seat Org");
        Plan plan = seedPlan("pro");
        Subscription sub = seedSubscriptionWithSeats(org.getId(), plan.getId(), 2);
        String jti = issuer.issue(sub.getId(), 30, null).jti();

        heartbeat(org.getId(), jti, "node-A").andExpect(status().isOk());
        heartbeat(org.getId(), jti, "node-B").andExpect(status().isOk());

        // A third distinct node would exceed the 2-seat limit -> 409, no third row.
        heartbeat(org.getId(), jti, "node-C").andExpect(status().isConflict());
        assertThat(activationRepo.findByJtiAndNodeId(jti, "node-C")).isEmpty();
        assertThat(activationRepo.findByJtiOrderByLastSeenAtDesc(jti)).hasSize(2);

        // An already-activated node may keep beating (renews its lease, never a new seat).
        heartbeat(org.getId(), jti, "node-A").andExpect(status().isOk());
        assertThat(activationService.activeSeatCount(jti)).isEqualTo(2);
    }

    @Test
    void heartbeatForForeignOrg_isRejected() throws Exception {
        Organization orgA = seedOrg("Tenant A");
        Organization orgB = seedOrg("Tenant B");
        Plan plan = seedPlan("pro");
        Subscription subB = seedSubscriptionWithSeats(orgB.getId(), plan.getId(), 5);
        String jtiB = issuer.issue(subB.getId(), 30, null).jti();

        // An API key bound to org A cannot heartbeat org B's license.
        int statusCode = heartbeat(orgA.getId(), jtiB, "node-x").andReturn().getResponse().getStatus();
        assertThat(statusCode).as("cross-tenant heartbeat must be denied, was %s", statusCode)
                .isIn(403, 404);
        assertThat(activationRepo.findByJtiOrderByLastSeenAtDesc(jtiB)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 3. Trial issuance flags the token TRIAL with a short TTL
    // ------------------------------------------------------------------

    @Test
    void issueTrial_marksTokenAsTrial() {
        Organization org = seedOrg("Trial Org");
        Plan plan = seedNewPlan("trialplan-" + rnd(), 365);
        Subscription sub = seedSubscriptionWithSeats(org.getId(), plan.getId(), 3);

        LicenseIssuer.IssuedLicense trial = issuer.issueTrial(sub.getId(), 7, null);
        LicenseToken token = tokenRepo.findByJti(trial.jti()).orElseThrow();

        assertThat(token.getLicenseType()).isEqualTo(LicenseToken.LicenseType.TRIAL);
        // 7-day trial TTL, not the plan's 365-day default.
        assertThat(token.getExpiresAt()).isBefore(token.getIssuedAt().plusDays(8));
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt().plusDays(6));

        // A standard issue stays STANDARD.
        LicenseIssuer.IssuedLicense std = issuer.issue(sub.getId(), 30, null);
        assertThat(tokenRepo.findByJti(std.jti()).orElseThrow().getLicenseType())
                .isEqualTo(LicenseToken.LicenseType.STANDARD);
    }

    // ------------------------------------------------------------------
    // 4. Lifecycle scheduler: ACTIVE -> EXPIRED transition + expiring warning
    // ------------------------------------------------------------------

    @Test
    void lifecycle_transitionsPastDueToExpired_andEmitsEvent() {
        Organization org = seedOrg("Lifecycle Org");
        Plan plan = seedPlan("pro");
        Subscription sub = seedSubscriptionWithSeats(org.getId(), plan.getId(), 5);

        // A token already past its expiry, still marked ACTIVE.
        String jti = seedToken(sub.getId(), LicenseToken.Status.ACTIVE,
                OffsetDateTime.now().minusDays(2), OffsetDateTime.now().minusDays(1));

        int expired = scheduler.expirePastDue();
        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(tokenRepo.findByJti(jti).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.EXPIRED);

        assertThat(outboxEventsFor(jti, "license.expired")).isNotEmpty();
    }

    @Test
    void lifecycle_emitsExpiringWarningOnceForTokenInWindow() {
        Organization org = seedOrg("Warn Org");
        Plan plan = seedPlan("pro");
        Subscription sub = seedSubscriptionWithSeats(org.getId(), plan.getId(), 5);

        // ACTIVE token expiring in 3 days -> inside the default P14D warning window.
        String jti = seedToken(sub.getId(), LicenseToken.Status.ACTIVE,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(3));

        int warned = scheduler.warnExpiring();
        assertThat(warned).isGreaterThanOrEqualTo(1);
        assertThat(outboxEventsFor(jti, "license.expiring")).hasSize(1);

        // A second sweep must NOT re-warn the same token.
        scheduler.warnExpiring();
        assertThat(outboxEventsFor(jti, "license.expiring")).hasSize(1);

        // The token is untouched (still ACTIVE) by a warning.
        assertThat(tokenRepo.findByJti(jti).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.ACTIVE);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions heartbeat(UUID apiKeyOrgId,
                                                                          String jti,
                                                                          String nodeId) throws Exception {
        return mockMvc.perform(post("/api/v1/licenses/{jti}/heartbeat", jti)
                .with(asApiKey(apiKeyOrgId, "usage.ingest"))
                .contentType("application/json")
                .content("{\"nodeId\":\"" + nodeId + "\"}"));
    }

    private Subscription seedSubscriptionWithSeats(UUID orgId, UUID planId, int seats) {
        OffsetDateTime now = OffsetDateTime.now();
        Subscription s = Subscription.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .planId(planId)
                .status(Subscription.Status.ACTIVE)
                .startsAt(now)
                .endsAt(now.plusYears(1))
                .seats(seats)
                .notes("seeded by LicenseHeartbeatActivationIT")
                .createdAt(now)
                .build();
        return subscriptionRepository.save(s);
    }

    private String seedToken(UUID subscriptionId, LicenseToken.Status status,
                             OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        String jti = "lic_" + UUID.randomUUID().toString().replace("-", "");
        LicenseToken t = LicenseToken.builder()
                .id(Ids.newId())
                .jti(jti)
                .subscriptionId(subscriptionId)
                .kid("test-kid")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .status(status)
                .licenseType(LicenseToken.LicenseType.STANDARD)
                .build();
        tokenRepo.save(t);
        return jti;
    }

    private List<OutboxEvent> outboxEventsFor(String jti, String eventType) {
        return outboxRepo.findAll().stream()
                .filter(e -> "license_token".equals(e.getAggregateType()))
                .filter(e -> jti.equals(e.getAggregateId()))
                .filter(e -> eventType.equals(e.getEventType()))
                .toList();
    }
}
