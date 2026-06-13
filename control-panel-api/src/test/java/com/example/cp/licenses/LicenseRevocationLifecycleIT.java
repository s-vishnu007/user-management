package com.example.cp.licenses;

import com.example.cp.common.Ids;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.example.cp.subscriptions.SubscriptionService;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Audit P1-5 / P1-8 / P2 lifecycle:
 * <ul>
 *   <li>Suspend/cancel cascade {@code markRevoked()} to all ACTIVE licenses of the subscription so
 *       they reach the CRL.</li>
 *   <li>Heartbeats for a non-ACTIVE subscription are rejected (no seat held).</li>
 *   <li>A heartbeat cannot un-revoke a license (guarded conditional last-seen update).</li>
 *   <li>Lifecycle expiry transition is atomic and warns at most once via the durable marker.</li>
 * </ul>
 */
class LicenseRevocationLifecycleIT extends AbstractIntegrationTest {

    @Autowired private LicenseIssuer issuer;
    @Autowired private LicenseTokenRepository tokenRepo;
    @Autowired private LicenseRevocationService revocationService;
    @Autowired private ActivationService activationService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subRepo;

    @Test
    void suspend_revokesAllActiveLicensesOfSubscription() {
        Organization org = seedOrg("Susp Org " + rnd());
        Plan plan = seedNewPlan("suspplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());

        String jti1 = issuer.issue(sub.getId(), 30, null).jti();
        String jti2 = issuer.issue(sub.getId(), 30, null).jti();
        assertThat(tokenRepo.findByJti(jti1).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.ACTIVE);

        subscriptionService.suspend(sub.getId(), "non-payment");

        assertThat(tokenRepo.findByJti(jti1).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.REVOKED);
        assertThat(tokenRepo.findByJti(jti2).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.REVOKED);
        // Both jtis are now on the CRL (still-unexpired revocations).
        assertThat(revocationService.listActiveRevocations(OffsetDateTime.now())
                .stream().map(LicenseToken::getJti))
                .contains(jti1, jti2);
    }

    @Test
    void heartbeatForNonActiveSubscription_isRejected() throws Exception {
        Organization org = seedOrg("HB Susp Org " + rnd());
        Plan plan = seedNewPlan("hbsuspplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        // Seed a still-ACTIVE token, then flip ONLY the subscription to SUSPENDED directly (bypassing
        // the cascade) so we exercise the heartbeat's defense-in-depth subscription-status guard.
        String jti = seedActiveToken(sub.getId());
        sub.setStatus(Subscription.Status.SUSPENDED);
        subRepo.save(sub);

        // Heartbeat must be rejected because the owning subscription is not ACTIVE (409).
        int statusCode = mockMvc.perform(post("/api/v1/licenses/{jti}/heartbeat", jti)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content("{\"nodeId\":\"node-1\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(statusCode).isEqualTo(409);
    }

    @Test
    void heartbeat_cannotUnrevokeLicense() throws Exception {
        Organization org = seedOrg("Unrevoke Org " + rnd());
        Plan plan = seedNewPlan("unrevokeplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = issuer.issue(sub.getId(), 30, null).jti();

        // Beat once so an activation exists.
        mockMvc.perform(post("/api/v1/licenses/{jti}/heartbeat", jti)
                .with(asApiKey(org.getId(), "usage.ingest"))
                .contentType("application/json")
                .content("{\"nodeId\":\"node-1\"}"));

        // Revoke directly, then a subsequent heartbeat must NOT flip it back to ACTIVE.
        revocationService.markRevoked(jti, "compromised", null);
        assertThat(tokenRepo.findByJti(jti).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.REVOKED);

        int statusCode = mockMvc.perform(post("/api/v1/licenses/{jti}/heartbeat", jti)
                        .with(asApiKey(org.getId(), "usage.ingest"))
                        .contentType("application/json")
                        .content("{\"nodeId\":\"node-1\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(statusCode).isEqualTo(400); // "License is revoked"

        LicenseToken after = tokenRepo.findByJti(jti).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LicenseToken.Status.REVOKED);
        assertThat(after.getRevokedAt()).isNotNull();
    }

    @Test
    void markRevoked_isIdempotent() {
        Organization org = seedOrg("Idem Org " + rnd());
        Plan plan = seedNewPlan("idemplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = issuer.issue(sub.getId(), 30, null).jti();

        revocationService.markRevoked(jti, "first", null);
        OffsetDateTime firstRevokedAt = tokenRepo.findByJti(jti).orElseThrow().getRevokedAt();
        // A second revoke is a no-op (does not move revoked_at).
        revocationService.markRevoked(jti, "second", null);
        assertThat(tokenRepo.findByJti(jti).orElseThrow().getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    private String seedActiveToken(UUID subscriptionId) {
        String jti = "lic_" + UUID.randomUUID().toString().replace("-", "");
        LicenseToken t = LicenseToken.builder()
                .id(Ids.newId())
                .jti(jti)
                .subscriptionId(subscriptionId)
                .kid("test-kid")
                .issuedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(30))
                .status(LicenseToken.Status.ACTIVE)
                .licenseType(LicenseToken.LicenseType.STANDARD)
                .build();
        return tokenRepo.save(t).getJti();
    }
}
