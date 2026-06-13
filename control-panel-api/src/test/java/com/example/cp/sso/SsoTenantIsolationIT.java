package com.example.cp.sso;

import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant-isolation coverage for the SSO provider delete/test endpoints (audit P1-2).
 *
 * <p>Before the fix, {@code DELETE /orgs/{orgId}/sso/{id}} and {@code POST /orgs/{orgId}/sso/{id}/test}
 * only proved the caller managed the PATH org, then resolved the provider by {@code id} alone — so an
 * OWNER/ADMIN of org A could delete or probe org B's SSO provider given its UUID. The fix scopes both
 * operations to {@code (id, orgId)} via {@code findByIdAndOrgId} and returns 404 on a mismatch, while
 * leaving the cross-tenant row untouched.
 */
class SsoTenantIsolationIT extends AbstractIntegrationTest {

    @Autowired private SsoProviderRepository ssoProviderRepository;

    /** Seeds an enabled OIDC provider for {@code orgId} and returns it. */
    private SsoProvider seedProvider(UUID orgId) {
        SsoProvider p = SsoProvider.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .type(SsoProvider.Type.OIDC)
                .configJson("{\"issuer\":\"https://idp.example.com\"}")
                .enabled(true)
                .createdAt(OffsetDateTime.now())
                .build();
        return ssoProviderRepository.save(p);
    }

    @Test
    void adminOfOrgA_cannotDeleteOrgBProvider_returns404_andRowSurvives() throws Exception {
        Organization orgA = seedOrg("Org A SSO");
        Organization orgB = seedOrg("Org B SSO");

        // Provider belongs to ORG B.
        SsoProvider providerB = seedProvider(orgB.getId());

        // Caller is an ADMIN of ORG A only.
        var admin = seedUser("ssoadmin-" + rnd() + "@a.com", "A Admin", false);
        addOrgMember(orgA.getId(), admin.getId(), OrgMember.Role.ADMIN);

        // Path org is A, but the target id belongs to B -> 404 (existence non-disclosure), not a delete.
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/sso/{id}", orgA.getId(), providerB.getId())
                        .with(asUser(admin)))
                .andExpect(status().isNotFound());

        // The cross-tenant provider must still exist.
        assertThat(ssoProviderRepository.findById(providerB.getId())).isPresent();
    }

    @Test
    void adminOfOrgA_cannotTestOrgBProvider_returns404() throws Exception {
        Organization orgA = seedOrg("Org A SSO Test");
        Organization orgB = seedOrg("Org B SSO Test");
        SsoProvider providerB = seedProvider(orgB.getId());

        var admin = seedUser("ssoadmin2-" + rnd() + "@a.com", "A Admin", false);
        addOrgMember(orgA.getId(), admin.getId(), OrgMember.Role.ADMIN);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/sso/{id}/test", orgA.getId(), providerB.getId())
                        .with(asUser(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanDeleteOwnOrgProvider() throws Exception {
        Organization org = seedOrg("Org Own SSO");
        SsoProvider provider = seedProvider(org.getId());

        var admin = seedUser("ssoadmin3-" + rnd() + "@own.com", "Own Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/sso/{id}", org.getId(), provider.getId())
                        .with(asUser(admin)))
                .andExpect(status().isNoContent());

        assertThat(ssoProviderRepository.findById(provider.getId())).isEmpty();
    }
}
