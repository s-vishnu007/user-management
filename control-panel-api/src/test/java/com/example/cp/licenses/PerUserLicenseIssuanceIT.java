package com.example.cp.licenses;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The re-architected per-user license flow: a license is issued TO a user INSIDE an org with a
 * hand-picked RBAC grant set (no plan / no subscription). Verifies issuance to an existing member,
 * invite-by-email provisioning, the JWT claim shape (subject = user, permissions baked in, no plan/
 * subscription claims), the org-scoped listing, the assignable-grants catalog, download + revoke on
 * an org-anchored token, and that a non-admin cannot issue.
 */
class PerUserLicenseIssuanceIT extends AbstractIntegrationTest {

    @Autowired private LicenseTokenRepository tokenRepo;
    @Autowired private OrgMemberRepository memberRepo;

    private JsonNode decodeClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readTree(payload);
    }

    @Test
    void issuesPerUserLicense_withChosenGrants_andUserSubjectClaims() throws Exception {
        Organization org = seedOrg("PU Org " + rnd());
        User member = seedUser("pu-member-" + rnd() + "@example.com", "Pat Member", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.MEMBER);
        User superAdmin = seedUser("pu-super-" + rnd() + "@example.com", "PU Super", true);

        String body = """
                {"userId":"%s","permissions":["license.read","usage.read"],"roleCodes":["VIEWER"],"ttlDays":30}
                """.formatted(member.getId());

        MvcResult res = mockMvc.perform(post("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jti").exists())
                .andExpect(jsonPath("$.license").exists())
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andReturn();

        JsonNode out = objectMapper.readTree(res.getResponse().getContentAsString());
        String jti = out.get("jti").asText();
        String jwt = out.get("license").asText();

        // Token row is org+user anchored with the permission snapshot; no subscription.
        LicenseToken token = tokenRepo.findByJti(jti).orElseThrow();
        assertThat(token.getOrgId()).isEqualTo(org.getId());
        assertThat(token.getUserId()).isEqualTo(member.getId());
        assertThat(token.getSubscriptionId()).isNull();
        assertThat(token.getSubjectEmail()).isEqualTo(member.getEmail());
        assertThat(token.getPermissions()).contains("license.read").contains("usage.read");

        // JWT subject is the user; permissions are baked in; there is no plan / subscription claim.
        JsonNode claims = decodeClaims(jwt);
        assertThat(claims.get("sub").asText()).isEqualTo(member.getId().toString());
        assertThat(claims.get("org_id").asText()).isEqualTo(org.getId().toString());
        assertThat(claims.get("user").get("email").asText()).isEqualTo(member.getEmail());
        assertThat(claims.has("plan")).isFalse();
        assertThat(claims.has("subscription_id")).isFalse();
        assertThat(claims.get("permissions").toString()).contains("license.read");
    }

    @Test
    void issueByEmail_provisionsUserAndOrgMembership() throws Exception {
        Organization org = seedOrg("PU Invite Org " + rnd());
        User superAdmin = seedUser("pu-inv-super-" + rnd() + "@example.com", "PU Inv Super", true);
        String newEmail = "pu-invitee-" + rnd() + "@example.com";

        String body = """
                {"email":"%s","permissions":["license.read"]}
                """.formatted(newEmail);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        Optional<User> provisioned = userRepository.findByEmail(newEmail);
        assertThat(provisioned).isPresent();
        assertThat(memberRepo.findByOrgIdAndUserId(org.getId(), provisioned.get().getId())).isPresent();
    }

    @Test
    void listByOrg_returnsIssuedLicense_withSubjectAndGrants() throws Exception {
        Organization org = seedOrg("PU List Org " + rnd());
        User member = seedUser("pu-list-" + rnd() + "@example.com", "Lee List", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.MEMBER);
        User superAdmin = seedUser("pu-list-super-" + rnd() + "@example.com", "PU List Super", true);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"permissions\":[\"license.read\"]}".formatted(member.getId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asSuperAdmin(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectEmail").value(member.getEmail()))
                .andExpect(jsonPath("$[0].permissions[0]").value("license.read"))
                .andExpect(jsonPath("$[0].orgId").value(org.getId().toString()));
    }

    @Test
    void assignableGrants_exposesRolesAndPermissions() throws Exception {
        User any = seedUser("pu-grants-" + rnd() + "@example.com", "Grant Reader", false);
        mockMvc.perform(get("/api/v1/licenses/assignable-grants")
                        .with(asUser(any)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.permissions[0].code").exists());
    }

    @Test
    void orgAnchoredLicense_canBeDownloadedAndRevoked() throws Exception {
        Organization org = seedOrg("PU DR Org " + rnd());
        User member = seedUser("pu-dr-" + rnd() + "@example.com", "Dee Revoke", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.MEMBER);
        User superAdmin = seedUser("pu-dr-super-" + rnd() + "@example.com", "PU DR Super", true);

        MvcResult res = mockMvc.perform(post("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"permissions\":[\"license.read\"]}".formatted(member.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        String jti = objectMapper.readTree(res.getResponse().getContentAsString()).get("jti").asText();

        // Download is a pure read of the org-anchored artifact (tenant access resolves via org_id).
        mockMvc.perform(get("/api/v1/licenses/{jti}/download", jti)
                        .with(asSuperAdmin(superAdmin)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/licenses/{jti}/revoke", jti)
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isNoContent());

        assertThat(tokenRepo.findByJti(jti).orElseThrow().getStatus())
                .isEqualTo(LicenseToken.Status.REVOKED);
    }

    @Test
    void nonAdminMember_cannotIssue() throws Exception {
        Organization org = seedOrg("PU Authz Org " + rnd());
        User memberOnly = seedUser("pu-authz-" + rnd() + "@example.com", "Mo Member", false);
        addOrgMember(org.getId(), memberOnly.getId(), OrgMember.Role.MEMBER);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asUser(memberOnly))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x-%s@example.com\",\"permissions\":[\"license.read\"]}".formatted(rnd())))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgAdmin_canIssue() throws Exception {
        Organization org = seedOrg("PU Admin Org " + rnd());
        User admin = seedUser("pu-admin-" + rnd() + "@example.com", "Ada Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/licenses", org.getId())
                        .with(asUser(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"adm-%s@example.com\",\"permissions\":[\"license.read\"]}".formatted(rnd())))
                .andExpect(status().isCreated());
    }
}
