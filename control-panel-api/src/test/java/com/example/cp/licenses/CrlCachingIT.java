package com.example.cp.licenses;

import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit P2: the signed CRL endpoint is cached so it is not re-scanned + re-signed on every
 * (anonymous) request, and is regenerated when the revoked set changes.
 */
class CrlCachingIT extends AbstractIntegrationTest {

    @Test
    void crl_isCachedBetweenRequests_andRegeneratesAfterRevocation() throws Exception {
        Organization org = seedOrg("CRL Cache Org " + rnd());
        Plan plan = seedNewPlan("crlcacheplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        User superAdmin = seedUser("crlcache-super-" + rnd() + "@example.com", "CRL Cache", true);

        // Two back-to-back fetches with no revocation change must return the identical signed JWS
        // (proves the result is cached, not re-signed each time — a fresh sign would change the iat).
        String first = fetchCrl();
        String second = fetchCrl();
        assertThat(second).isEqualTo(first);

        // Issue + revoke a license -> the revoked set changes -> the CRL must regenerate and list it.
        MvcResult issued = mockMvc.perform(post("/api/v1/subscriptions/{subId}/licenses", sub.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String jti = objectMapper.readTree(issued.getResponse().getContentAsString()).get("jti").asText();

        mockMvc.perform(post("/api/v1/licenses/{jti}/revoke", jti)
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNoContent());

        String afterRevoke = fetchCrl();
        assertThat(afterRevoke).isNotEqualTo(first);
        // The freshly revoked jti now appears in the signed claims.
        JsonNode claims = decodeClaims(afterRevoke);
        assertThat(claims.get("revoked").toString()).contains(jti);
    }

    private String fetchCrl() throws Exception {
        return mockMvc.perform(get("/api/v1/licenses/crl"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode decodeClaims(String jws) throws Exception {
        String payload = jws.split("\\.")[1];
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(payload);
        return objectMapper.readTree(decoded);
    }
}
