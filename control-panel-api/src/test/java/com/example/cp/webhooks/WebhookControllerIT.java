package com.example.cp.webhooks;

import com.example.cp.keys.KeyEncryptor;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP + tenancy integration tests for {@link WebhookController} on {@link AbstractIntegrationTest}.
 *
 * <p>Proves: CRUD is gated by {@code @tenantAccess.canManageOrg(#orgId)} (an ADMIN of org A cannot
 * touch org B's webhooks); the signing secret is returned exactly once at create and never echoed on
 * list; the secret is stored encrypted at rest (decryptable only via {@link KeyEncryptor}); the URL
 * is validated through the SSRF guard (loopback/http rejected); and delete is org-scoped (cross-tenant
 * delete is a 404).
 */
class WebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private WebhookSubscriptionRepository webhookRepo;
    @Autowired private KeyEncryptor keyEncryptor;

    @Test
    void create_returnsSecretOnce_storesEncrypted_andListExcludesSecret() throws Exception {
        Organization org = seedOrg("Webhook Org");
        User admin = seedUser("wh-admin-" + rnd() + "@example.com", "WH Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        String createBody = """
                {"url":"https://203.0.113.10/cp","eventTypes":"LicenseRevoked, SubscriptionActivated"}
                """;
        String response = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", org.getId())
                        .with(asUser(admin))
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.webhook.url", is("https://203.0.113.10/cp")))
                .andExpect(jsonPath("$.webhook.eventTypes", is("LicenseRevoked,SubscriptionActivated")))
                .andExpect(jsonPath("$.webhook.active", is(true)))
                .andExpect(jsonPath("$.secret").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(response);
        String plaintextSecret = node.get("secret").asText();
        UUID webhookId = UUID.fromString(node.get("webhook").get("id").asText());
        assertThat(plaintextSecret).isNotBlank();

        // Stored encrypted at rest: the persisted blob decrypts back to the returned plaintext, and
        // the raw blob is NOT the plaintext bytes.
        WebhookSubscription saved = webhookRepo.findById(webhookId).orElseThrow();
        assertThat(saved.getSecretEnc()).isNotNull();
        assertThat(new String(saved.getSecretEnc(), StandardCharsets.UTF_8)).isNotEqualTo(plaintextSecret);
        assertThat(new String(keyEncryptor.decrypt(saved.getSecretEnc()), StandardCharsets.UTF_8))
                .isEqualTo(plaintextSecret);

        // List never returns the secret.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks", org.getId())
                        .with(asUser(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(webhookId.toString())))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].secretEnc").doesNotExist());
    }

    @Test
    void crud_isTenantScoped_adminOfOrgA_cannotTouchOrgB() throws Exception {
        Organization orgA = seedOrg("Org A");
        Organization orgB = seedOrg("Org B");

        User adminA = seedUser("wh-a-" + rnd() + "@example.com", "Admin A", false);
        addOrgMember(orgA.getId(), adminA.getId(), OrgMember.Role.ADMIN);

        User adminB = seedUser("wh-b-" + rnd() + "@example.com", "Admin B", false);
        addOrgMember(orgB.getId(), adminB.getId(), OrgMember.Role.ADMIN);

        // adminA creates a webhook in org A.
        String resp = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgA.getId())
                        .with(asUser(adminA))
                        .contentType("application/json")
                        .content("{\"url\":\"https://203.0.113.10/hook\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID webhookAId = UUID.fromString(objectMapper.readTree(resp).get("webhook").get("id").asText());

        // adminB cannot list org A's webhooks.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks", orgA.getId())
                        .with(asUser(adminB)))
                .andExpect(status().isForbidden());

        // adminB cannot create in org A.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgA.getId())
                        .with(asUser(adminB))
                        .contentType("application/json")
                        .content("{\"url\":\"https://evil.example.com/hook\"}"))
                .andExpect(status().isForbidden());

        // adminB cannot delete org A's webhook (forbidden at the org gate, before any row lookup).
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/webhooks/{id}", orgA.getId(), webhookAId)
                        .with(asUser(adminB)))
                .andExpect(status().isForbidden());

        // A webhook id from org A, addressed under org B's path by org B's admin, is a 404 (id not in
        // org B) — never a cross-tenant delete.
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/webhooks/{id}", orgB.getId(), webhookAId)
                        .with(asUser(adminB)))
                .andExpect(status().isNotFound());

        // org A's webhook still exists.
        assertThat(webhookRepo.findById(webhookAId)).isPresent();

        // adminA can delete it.
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/webhooks/{id}", orgA.getId(), webhookAId)
                        .with(asUser(adminA)))
                .andExpect(status().isNoContent());
        assertThat(webhookRepo.findById(webhookAId)).isEmpty();
    }

    @Test
    void create_rejectsNonHttpsAndLoopbackUrls() throws Exception {
        Organization org = seedOrg("Guard Org");
        User admin = seedUser("wh-guard-" + rnd() + "@example.com", "Guard Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        // Plain http is rejected (https-only).
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", org.getId())
                        .with(asUser(admin))
                        .contentType("application/json")
                        .content("{\"url\":\"http://hooks.example.com/cp\"}"))
                .andExpect(status().isBadRequest());

        // Loopback is rejected (SSRF guard).
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", org.getId())
                        .with(asUser(admin))
                        .contentType("application/json")
                        .content("{\"url\":\"https://127.0.0.1/cp\"}"))
                .andExpect(status().isBadRequest());

        assertThat(webhookRepo.findByOrgIdOrderByCreatedAtDesc(org.getId())).isEmpty();
    }

    @Test
    void create_superAdmin_canManageAnyOrg() throws Exception {
        Organization org = seedOrg("SA Org");
        User sa = seedUser("wh-sa-" + rnd() + "@example.com", "Super", true);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", org.getId())
                        .with(asSuperAdmin(sa))
                        .contentType("application/json")
                        .content("{\"url\":\"https://203.0.113.10/sa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.webhook.eventTypes").value(nullValue())); // null = all events
    }
}
