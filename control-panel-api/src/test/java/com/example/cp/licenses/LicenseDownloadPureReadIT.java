package com.example.cp.licenses;

import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit P1-4: {@code GET /licenses/{jti}/download} must be a PURE READ — it returns the exact
 * persisted artifact for the requested jti (no re-issue, no new jti / row / outbox event), and
 * 410 GONE when no artifact is downloadable (revoked / never-persisted).
 */
class LicenseDownloadPureReadIT extends AbstractIntegrationTest {

    @Autowired private LicenseTokenRepository tokenRepo;
    @Autowired private LicenseArtifactRepository artifactRepo;

    @Test
    void download_returnsStoredArtifact_sameJti_noNewRow() throws Exception {
        Organization org = seedOrg("DL Org " + rnd());
        Plan plan = seedNewPlan("dlplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        User superAdmin = seedUser("dl-super-" + rnd() + "@example.com", "DL Super", true);

        MvcResult issued = mockMvc.perform(post("/api/v1/subscriptions/{subId}/licenses", sub.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode issueBody = objectMapper.readTree(issued.getResponse().getContentAsString());
        String jti = issueBody.get("jti").asText();
        String issuedJwt = issueBody.get("license").asText();

        long rowsBefore = tokenRepo.count();
        assertThat(artifactRepo.findByJti(jti)).isPresent();

        // Download twice: the body must embed the SAME jti's stored JWT, and no new token row appears.
        for (int i = 0; i < 2; i++) {
            MvcResult dl = mockMvc.perform(get("/api/v1/licenses/{jti}/download", jti)
                            .with(asSuperAdmin(superAdmin)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode envelope = objectMapper.readTree(dl.getResponse().getContentAsByteArray());
            assertThat(envelope.get("license").asText()).isEqualTo(issuedJwt);
        }
        assertThat(tokenRepo.count()).isEqualTo(rowsBefore);
    }

    @Test
    void download_ofRevokedLicense_isGone_andDoesNotReissue() throws Exception {
        Organization org = seedOrg("DL Rev Org " + rnd());
        Plan plan = seedNewPlan("dlrevplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        User superAdmin = seedUser("dl-rev-super-" + rnd() + "@example.com", "DL Rev Super", true);

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
                        .content("{\"reason\":\"compromised\"}"))
                .andExpect(status().isNoContent());

        long rowsBefore = tokenRepo.count();
        // A revoked license is GONE (410) and downloading it must NOT mint a replacement.
        mockMvc.perform(get("/api/v1/licenses/{jti}/download", jti)
                        .with(asSuperAdmin(superAdmin)))
                .andExpect(status().isGone());
        assertThat(tokenRepo.count()).isEqualTo(rowsBefore);
    }

    @Test
    void download_withNoStoredArtifact_isGone_noReissue() throws Exception {
        // Simulate a legacy token that has a token row but no persisted artifact (pre-fix data):
        // the download must be GONE, never a fresh re-issue.
        Organization org = seedOrg("DL Legacy Org " + rnd());
        Plan plan = seedNewPlan("dllegacy-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        User superAdmin = seedUser("dl-legacy-super-" + rnd() + "@example.com", "DL Legacy", true);

        String jti = "lic_" + java.util.UUID.randomUUID().toString().replace("-", "");
        LicenseToken t = LicenseToken.builder()
                .id(com.example.cp.common.Ids.newId())
                .jti(jti)
                .subscriptionId(sub.getId())
                .kid("legacy-kid")
                .issuedAt(java.time.OffsetDateTime.now())
                .expiresAt(java.time.OffsetDateTime.now().plusDays(30))
                .status(LicenseToken.Status.ACTIVE)
                .licenseType(LicenseToken.LicenseType.STANDARD)
                .build();
        tokenRepo.save(t);

        long rowsBefore = tokenRepo.count();
        mockMvc.perform(get("/api/v1/licenses/{jti}/download", jti)
                        .with(asSuperAdmin(superAdmin)))
                .andExpect(status().isGone());
        assertThat(tokenRepo.count()).isEqualTo(rowsBefore);
    }
}
