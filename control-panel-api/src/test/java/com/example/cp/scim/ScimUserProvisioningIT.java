package com.example.cp.scim;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP + tenancy integration tests for the SCIM 2.0 endpoints on {@link AbstractIntegrationTest}.
 *
 * <p>Proves the bucket contract end-to-end:
 * <ul>
 *   <li>provision via an org-bound {@code scim.manage} api-key creates a {@code users} row, an
 *       {@code org_members} membership, and a {@link ScimUserMapping} — and returns a SCIM user with id;</li>
 *   <li>deprovision (DELETE) suspends the user and bumps {@code token_version} (session revocation);</li>
 *   <li>cross-org isolation: org A's key cannot read/list/delete a resource provisioned in org B;</li>
 *   <li>filtering ({@code userName eq}/{@code externalId eq}) and get-by-id are org-scoped;</li>
 *   <li>the gate denies a key lacking {@code scim.manage}.</li>
 * </ul>
 * The api-key principal is injected via {@code asApiKey(orgId, "scim.manage")} (mirrors what
 * {@code ApiKeyAuthFilter} builds), so {@code @scimOrg.callerOrgId()} resolves the bound org and the
 * {@code @tenantAccess} api-key org-equality gate is exercised without minting a real key.
 */
class ScimUserProvisioningIT extends AbstractIntegrationTest {

    @Autowired private ScimUserMappingRepository mappingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrgMemberRepository orgMemberRepository;

    @Test
    void provision_createsUserMembershipAndMapping_thenDeprovision_suspendsAndRevokesSessions() throws Exception {
        Organization org = seedOrg("SCIM Org");
        String email = "scim-" + rnd() + "@example.com";

        String createBody = """
                {
                  "schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "userName":"%s",
                  "externalId":"ext-%s",
                  "name":{"formatted":"SCIM User"},
                  "active":true
                }
                """.formatted(email, email);

        String response = mockMvc.perform(post("/scim/v2/Users")
                        .with(asApiKey(org.getId(), "scim.manage"))
                        .contentType("application/scim+json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.userName", is(email)))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.schemas[0]",
                        is("urn:ietf:params:scim:schemas:core:2.0:User")))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(response);
        UUID scimId = UUID.fromString(node.get("id").asText());

        // The SCIM resource id is the mapping id, not the raw user id.
        ScimUserMapping mapping = mappingRepository.findById(scimId).orElseThrow();
        assertThat(mapping.getOrgId()).isEqualTo(org.getId());
        assertThat(mapping.getExternalId()).isEqualTo("ext-" + email);

        // A control-panel user was created, ACTIVE, with token_version 0.
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(User.Status.ACTIVE);
        long tokenVersionBefore = user.getTokenVersion();
        assertThat(user.getId()).isEqualTo(mapping.getUserId());

        // An org membership was created.
        OrgMember member = orgMemberRepository.findByOrgIdAndUserId(org.getId(), user.getId()).orElseThrow();
        assertThat(member.getRole()).isEqualTo(OrgMember.Role.MEMBER);

        // GET by id returns the same resource.
        mockMvc.perform(get("/scim/v2/Users/{id}", scimId)
                        .with(asApiKey(org.getId(), "scim.manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(scimId.toString())))
                .andExpect(jsonPath("$.userName", is(email)));

        // DELETE deprovisions: user becomes SUSPENDED and token_version is bumped (session revocation).
        mockMvc.perform(delete("/scim/v2/Users/{id}", scimId)
                        .with(asApiKey(org.getId(), "scim.manage")))
                .andExpect(status().isNoContent());

        User afterDelete = userRepository.findById(user.getId()).orElseThrow();
        assertThat(afterDelete.getStatus()).isEqualTo(User.Status.SUSPENDED);
        assertThat(afterDelete.getTokenVersion()).isGreaterThan(tokenVersionBefore);
    }

    @Test
    void patch_activeFalse_deprovisions_andActiveTrue_reactivates() throws Exception {
        Organization org = seedOrg("SCIM Patch Org");
        String email = "scim-patch-" + rnd() + "@example.com";

        String scimId = provision(org.getId(), email, "ext-" + email);
        User user = userRepository.findByEmail(email).orElseThrow();
        long v0 = user.getTokenVersion();

        // PATCH active=false -> SUSPENDED + token_version bump.
        mockMvc.perform(patch("/scim/v2/Users/{id}", scimId)
                        .with(asApiKey(org.getId(), "scim.manage"))
                        .contentType("application/scim+json")
                        .content("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[{"op":"replace","path":"active","value":false}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));

        User suspended = userRepository.findById(user.getId()).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo(User.Status.SUSPENDED);
        assertThat(suspended.getTokenVersion()).isGreaterThan(v0);

        // PATCH active=true -> ACTIVE again.
        mockMvc.perform(patch("/scim/v2/Users/{id}", scimId)
                        .with(asApiKey(org.getId(), "scim.manage"))
                        .contentType("application/scim+json")
                        .content("""
                                {"Operations":[{"op":"replace","path":"active","value":true}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .isEqualTo(User.Status.ACTIVE);
    }

    @Test
    void list_filtersByUserNameAndExternalId_scopedToCallerOrg() throws Exception {
        Organization org = seedOrg("SCIM List Org");
        String emailA = "scim-list-a-" + rnd() + "@example.com";
        String emailB = "scim-list-b-" + rnd() + "@example.com";
        provision(org.getId(), emailA, "ext-a-" + rnd());
        String extB = "ext-b-" + rnd();
        provision(org.getId(), emailB, extB);

        // Unfiltered list returns both (totalResults >= 2).
        mockMvc.perform(get("/scim/v2/Users")
                        .param("startIndex", "1").param("count", "50")
                        .with(asApiKey(org.getId(), "scim.manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas[0]",
                        is("urn:ietf:params:scim:api:messages:2.0:ListResponse")))
                .andExpect(jsonPath("$.totalResults",
                        greaterThanOrEqualTo(2)));

        // eq filter on userName returns exactly one matching resource.
        mockMvc.perform(get("/scim/v2/Users")
                        .param("filter", "userName eq \"" + emailA + "\"")
                        .with(asApiKey(org.getId(), "scim.manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults", is(1)))
                .andExpect(jsonPath("$.Resources[0].userName", is(emailA)));

        // eq filter on externalId returns the other resource.
        mockMvc.perform(get("/scim/v2/Users")
                        .param("filter", "externalId eq \"" + extB + "\"")
                        .with(asApiKey(org.getId(), "scim.manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults", is(1)))
                .andExpect(jsonPath("$.Resources[0].userName", is(emailB)));
    }

    @Test
    void crossOrgIsolation_orgAKey_cannotReadListOrDeleteOrgBResource() throws Exception {
        Organization orgA = seedOrg("SCIM Org A");
        Organization orgB = seedOrg("SCIM Org B");
        String emailB = "scim-b-" + rnd() + "@example.com";
        String scimIdB = provision(orgB.getId(), emailB, "ext-b-" + rnd());

        // Org A's key cannot GET org B's resource (mapping id not in org A) -> 404.
        mockMvc.perform(get("/scim/v2/Users/{id}", scimIdB)
                        .with(asApiKey(orgA.getId(), "scim.manage")))
                .andExpect(status().isNotFound());

        // Org A's key cannot delete org B's resource -> 404 (never a cross-tenant deprovision).
        mockMvc.perform(delete("/scim/v2/Users/{id}", scimIdB)
                        .with(asApiKey(orgA.getId(), "scim.manage")))
                .andExpect(status().isNotFound());

        // Org B's user remains ACTIVE (org A could not touch it).
        User userB = userRepository.findByEmail(emailB).orElseThrow();
        assertThat(userB.getStatus()).isEqualTo(User.Status.ACTIVE);

        // Org A's filtered list cannot see org B's user.
        mockMvc.perform(get("/scim/v2/Users")
                        .param("filter", "userName eq \"" + emailB + "\"")
                        .with(asApiKey(orgA.getId(), "scim.manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults", is(0)));
    }

    @Test
    void gate_deniesKeyWithoutScimManageScope() throws Exception {
        Organization org = seedOrg("SCIM Deny Org");
        mockMvc.perform(get("/scim/v2/Users")
                        .with(asApiKey(org.getId(), "usage.read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void provision_duplicateExternalId_conflicts() throws Exception {
        Organization org = seedOrg("SCIM Dup Org");
        String ext = "ext-dup-" + rnd();
        provision(org.getId(), "scim-dup-1-" + rnd() + "@example.com", ext);

        String body = """
                {"userName":"scim-dup-2-%s@example.com","externalId":"%s","active":true}
                """.formatted(rnd(), ext);
        mockMvc.perform(post("/scim/v2/Users")
                        .with(asApiKey(org.getId(), "scim.manage"))
                        .contentType("application/scim+json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.scimType", is("uniqueness")))
                .andExpect(jsonPath("$.schemas[0]",
                        is("urn:ietf:params:scim:api:messages:2.0:Error")));
    }

    /** Helper: provision a user via the SCIM POST endpoint and return the resulting SCIM resource id. */
    private String provision(UUID orgId, String email, String externalId) throws Exception {
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"%s","externalId":"%s","name":{"formatted":"SCIM User"},"active":true}
                """.formatted(email, externalId);
        String resp = mockMvc.perform(post("/scim/v2/Users")
                        .with(asApiKey(orgId, "scim.manage"))
                        .contentType("application/scim+json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }
}
