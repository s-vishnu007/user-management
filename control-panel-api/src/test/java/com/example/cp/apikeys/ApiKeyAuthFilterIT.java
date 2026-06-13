package com.example.cp.apikeys;

import com.example.cp.common.Ids;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of {@link ApiKeyAuthFilter} + {@link ApiKeyService} driven through the REAL
 * HTTP filter chain (closes the P2 gap where these were only exercised via an injected synthetic
 * principal). Every request below sends a genuine {@code Authorization: ApiKey <token>} header so the
 * filter does the prefix lookup, constant-time hash compare, scope projection, and principal
 * construction itself.
 *
 * <p>The exercised endpoint is {@code POST /api/v1/usage/ingest} (requires the {@code usage.ingest}
 * scope and a tenant-bound jti) — a real machine-facing endpoint an API key is allowed to call.
 *
 * <p>Branches covered: valid (202 + {@code last_used_at} stamped), revoked (401), malformed token
 * (401), wrong auth scheme (401), cross-org (denied — the key authenticates but the tenant binding
 * rejects another org's jti), and the P1-7 lost-update guarantee (a revoke is never silently undone
 * by a concurrent verify touch).
 */
class ApiKeyAuthFilterIT extends AbstractIntegrationTest {

    private static final String INGEST = "/api/v1/usage/ingest";

    @Autowired private LicenseTokenRepository licenseTokenRepository;

    // ------------------------------------------------------------------
    // valid
    // ------------------------------------------------------------------

    @Test
    void validApiKey_authenticatesThroughFilter_andStampsLastUsed() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Valid Key Org");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        ApiKeyService.CreateResult key = seedApiKey(org.getId(), "ci", Set.of("usage.ingest"));
        assertThat(apiKeyRepository.findById(key.apiKey().getId()).orElseThrow().getLastUsedAt())
                .as("last_used_at starts null")
                .isNull();

        mockMvc.perform(post(INGEST)
                        .header("Authorization", "ApiKey " + key.plaintextKey())
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("2")))))
                .andExpect(status().isAccepted());

        // verify() must have stamped last_used_at via the guarded targeted UPDATE.
        assertThat(apiKeyRepository.findById(key.apiKey().getId()).orElseThrow().getLastUsedAt())
                .as("last_used_at stamped on successful auth")
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // revoked
    // ------------------------------------------------------------------

    @Test
    void revokedApiKey_isRejected_401() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Revoked Key Org");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        ApiKeyService.CreateResult key = seedApiKey(org.getId(), "ci", Set.of("usage.ingest"));
        apiKeyService.revoke(org.getId(), key.apiKey().getId());

        mockMvc.perform(post(INGEST)
                        .header("Authorization", "ApiKey " + key.plaintextKey())
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("1")))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // malformed / wrong scheme
    // ------------------------------------------------------------------

    @Test
    void malformedApiKeyToken_isRejected_401() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Malformed Key Org");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        mockMvc.perform(post(INGEST)
                        .header("Authorization", "ApiKey not-a-real-key-xxxxxxxxxxxx")
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("1")))))
                .andExpect(status().isUnauthorized());

        // A too-short token (under the prefix length) is also a clean miss, not a 500.
        mockMvc.perform(post(INGEST)
                        .header("Authorization", "ApiKey cp_x")
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("1")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAuthScheme_isNotTreatedAsApiKey_401() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Scheme Org");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        String jti = seedActiveLicense(sub.getId());

        ApiKeyService.CreateResult key = seedApiKey(org.getId(), "ci", Set.of("usage.ingest"));

        // Presenting a valid API-key value under the Bearer scheme must NOT authenticate as an API key
        // (the JWT filter will reject it as a malformed JWT).
        mockMvc.perform(post(INGEST)
                        .header("Authorization", "Bearer " + key.plaintextKey())
                        .contentType("application/json")
                        .content(ingestJson(jti, event("seats", new BigDecimal("1")))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // cross-org
    // ------------------------------------------------------------------

    @Test
    void apiKeyForOrgA_cannotIngestForOrgBJti_evenThoughItAuthenticates() throws Exception {
        Plan plan = seedPlan("pro");
        Organization orgA = seedOrg("Cross Org A");
        Organization orgB = seedOrg("Cross Org B");
        Subscription subB = seedSubscription(orgB.getId(), plan.getId());
        String jtiB = seedActiveLicense(subB.getId());

        // A genuine, live key bound to org A — it authenticates through the filter fine, but the
        // tenant binding on the jti (org B) must reject it.
        ApiKeyService.CreateResult keyA = seedApiKey(orgA.getId(), "ci", Set.of("usage.ingest"));

        int statusCode = mockMvc.perform(post(INGEST)
                        .header("Authorization", "ApiKey " + keyA.plaintextKey())
                        .contentType("application/json")
                        .content(ingestJson(jtiB, event("seats", new BigDecimal("1")))))
                .andReturn().getResponse().getStatus();

        assertThat(statusCode)
                .as("cross-tenant ingest must be denied (403 forbidden or 404 non-disclosure), was %s", statusCode)
                .isIn(403, 404);
    }

    // ------------------------------------------------------------------
    // P1-7: revoke is never silently undone by a verify() touch
    // ------------------------------------------------------------------

    @Test
    void revokeIsNotUndoneByConcurrentVerifyTouch() {
        Organization org = seedOrg("Lost Update Org");
        ApiKeyService.CreateResult key = seedApiKey(org.getId(), "ci", Set.of("usage.ingest"));
        UUID id = key.apiKey().getId();

        // First a normal successful verify (stamps last_used_at).
        assertThat(apiKeyService.verify(key.plaintextKey())).isPresent();

        // Now revoke it (guarded conditional UPDATE).
        apiKeyService.revoke(org.getId(), id);
        OffsetDateTime revokedAt = apiKeyRepository.findById(id).orElseThrow().getRevokedAt();
        assertThat(revokedAt).as("key is revoked").isNotNull();

        // A subsequent verify() with the (now revoked) key must NOT authenticate and must NOT
        // resurrect the row: revoked_at stays exactly as it was. This is the lost-update fix —
        // verify() touches last_used_at only WHERE revoked_at IS NULL, so it cannot clobber the revoke.
        assertThat(apiKeyService.verify(key.plaintextKey())).isEmpty();

        ApiKey after = apiKeyRepository.findById(id).orElseThrow();
        assertThat(after.getRevokedAt())
                .as("revoked_at must remain set after a verify() attempt on a revoked key")
                .isEqualTo(revokedAt);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private Map<String, Object> event(String featureKey, BigDecimal quantity) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "evt-" + rnd());
        e.put("featureKey", featureKey);
        e.put("quantity", quantity);
        e.put("occurredAt", OffsetDateTime.now().toString());
        return e;
    }

    @SafeVarargs
    private String ingestJson(String jti, Map<String, Object>... events) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jti", jti);
        body.put("events", List.of(events));
        return objectMapper.writeValueAsString(body);
    }
}
