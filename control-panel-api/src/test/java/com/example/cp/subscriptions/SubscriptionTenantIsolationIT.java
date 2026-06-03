package com.example.cp.subscriptions;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-tenant isolation example built on {@link AbstractIntegrationTest}.
 *
 * <p>Proves that {@code GET /api/v1/subscriptions/{id}} (guarded by
 * {@code @PreAuthorize("@tenantAccess.canReadSubscription(#id)")}) resolves the TARGET
 * subscription's owning org and authorizes against THAT org's membership — there is no global
 * authority bypass. An ADMIN of org A (a member of A only) is FORBIDDEN from reading org B's
 * subscription, while org B's own admin reads it successfully.
 *
 * <p>The forbidden case is exercised both via a directly-injected human principal ({@code asUser})
 * and via a real session JWT obtained through {@code POST /api/v1/auth/login}, demonstrating both
 * auth helpers reach the same {@code @tenantAccess} decision.
 */
class SubscriptionTenantIsolationIT extends AbstractIntegrationTest {

    @Test
    void adminOfOrgA_isForbidden_fromReadingOrgB_subscription_butOrgBAdminSucceeds() throws Exception {
        // --- seed two isolated tenants ---
        Organization orgA = seedOrg("Org A");
        Organization orgB = seedOrg("Org B");
        Plan plan = seedPlan("pro");

        User adminA = seedUser("admin-a-" + rnd() + "@example.com", "Admin A", false);
        addOrgMember(orgA.getId(), adminA.getId(), OrgMember.Role.ADMIN);
        grantRole(adminA.getId(), "ORG_ADMIN", orgA.getId());

        User adminB = seedUser("admin-b-" + rnd() + "@example.com", "Admin B", false);
        addOrgMember(orgB.getId(), adminB.getId(), OrgMember.Role.ADMIN);
        grantRole(adminB.getId(), "ORG_ADMIN", orgB.getId());

        var subB = seedSubscription(orgB.getId(), plan.getId());

        // --- org A's admin (injected principal) is FORBIDDEN from org B's subscription ---
        mockMvc.perform(get("/api/v1/subscriptions/{id}", subB.getId())
                        .with(asUser(adminA, "subscription.read")))
                .andExpect(status().isForbidden());

        // --- same denial via a REAL login JWT for org A's admin ---
        String tokenA = loginAndGetToken(adminA.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/subscriptions/{id}", subB.getId())
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isForbidden());

        // --- org B's own admin can read it (injected principal) ---
        mockMvc.perform(get("/api/v1/subscriptions/{id}", subB.getId())
                        .with(asUser(adminB, "subscription.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId", is(orgB.getId().toString())))
                .andExpect(jsonPath("$.id", is(subB.getId().toString())));

        // --- and via a REAL login JWT for org B's admin ---
        String tokenB = loginAndGetToken(adminB.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/subscriptions/{id}", subB.getId())
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId", is(orgB.getId().toString())));
    }
}
