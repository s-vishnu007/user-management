package com.example.cp.usage;

import com.example.cp.common.Ids;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant-isolation + correctness coverage for {@code GET /subscriptions/{subId}/usage} (audit P1-1
 * and the {@code getQuotaStatus} multi-period 500).
 *
 * <p>P1-1: the endpoint was gated only by {@code hasAuthority('subscription.read') or
 * hasAuthority('usage.read')} with no tenant predicate, and {@code usage.read} is org-mintable — so an
 * org-bound API key for org A could read org B's usage by passing B's subId. The fix AND-composes
 * {@code @tenantAccess.canReadSubscription(#subId)} so the caller is authorized against the TARGET
 * subscription's owning org.
 *
 * <p>getQuotaStatus: the per-feature quota query was a single-result {@code Optional}, but one row
 * accumulates per month — so after a second period it threw {@code IncorrectResultSizeDataAccessException}
 * -> 500. The fix returns a list; this asserts a two-period feature listing is 200 and returns both.
 */
class UsageListingTenantIsolationIT extends AbstractIntegrationTest {

    @Autowired private LicenseTokenRepository licenseTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String usageUrl(UUID subId) {
        return "/api/v1/subscriptions/" + subId + "/usage";
    }

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

    private void seedQuota(UUID subId, String featureKey, OffsetDateTime period, BigDecimal limit, BigDecimal consumed) {
        OffsetDateTime periodStart = monthStartUtc(period);
        jdbcTemplate.update("""
                INSERT INTO usage_quotas (subscription_id, feature_key, period_start, period_end, limit_value, consumed_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """, subId, featureKey, periodStart, periodStart.plusMonths(1), limit, consumed);
    }

    private static OffsetDateTime monthStartUtc(OffsetDateTime t) {
        OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    }

    // ------------------------------------------------------------------
    // P1-1: cross-tenant usage read is blocked
    // ------------------------------------------------------------------

    @Test
    void apiKeyForOrgA_cannotReadOrgBUsage() throws Exception {
        Plan plan = seedPlan("pro");
        Organization orgA = seedOrg("Usage Org A");
        Organization orgB = seedOrg("Usage Org B");

        Subscription subB = seedSubscription(orgB.getId(), plan.getId());
        seedActiveLicense(subB.getId());

        // Caller is an API key bound to ORG A holding the org-mintable usage.read scope.
        mockMvc.perform(get(usageUrl(subB.getId()))
                        .with(asApiKey(orgA.getId(), "usage.read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void userOfOrgA_cannotReadOrgBUsage_evenWithGlobalUsageReadAuthority() throws Exception {
        Plan plan = seedPlan("pro");
        Organization orgA = seedOrg("Usage Org A2");
        Organization orgB = seedOrg("Usage Org B2");

        Subscription subB = seedSubscription(orgB.getId(), plan.getId());

        var userA = seedUser("usageA-" + rnd() + "@a.com", "A User", false);
        addOrgMember(orgA.getId(), userA.getId(), OrgMember.Role.ADMIN);

        // Even holding the global usage.read authority, the tenant predicate denies the cross-org read.
        mockMvc.perform(get(usageUrl(subB.getId()))
                        .with(asUser(userA, "usage.read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKeyForCorrectOrg_canReadOwnUsage() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Usage Org Own");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        seedActiveLicense(sub.getId());

        mockMvc.perform(get(usageUrl(sub.getId()))
                        .with(asApiKey(org.getId(), "usage.read")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // getQuotaStatus: two periods for one feature no longer 500s
    // ------------------------------------------------------------------

    @Test
    void quotaStatusForFeature_withTwoPeriods_returns200AndBothRows() throws Exception {
        Plan plan = seedPlan("pro");
        Organization org = seedOrg("Usage Quota Periods");
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        seedActiveLicense(sub.getId());

        OffsetDateTime thisMonth = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime lastMonth = thisMonth.minusMonths(1);
        seedQuota(sub.getId(), "seats", lastMonth, new BigDecimal("100"), new BigDecimal("40"));
        seedQuota(sub.getId(), "seats", thisMonth, new BigDecimal("100"), new BigDecimal("12"));

        // Filtering by featureKey hits the previously single-result query path; two periods must NOT 500.
        String body = mockMvc.perform(get(usageUrl(sub.getId()))
                        .param("featureKey", "seats")
                        .with(asApiKey(org.getId(), "usage.read")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode quotas = objectMapper.readTree(body).get("quotas");
        assertThat(quotas).hasSize(2);
    }
}
