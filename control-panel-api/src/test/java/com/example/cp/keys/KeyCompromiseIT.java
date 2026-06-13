package com.example.cp.keys;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the COMPROMISED key-status feature:
 *
 * <ul>
 *   <li>{@code markCompromised(kid)} drops the key from {@code /.well-known/jwks.json} immediately,
 *       and — because it was ACTIVE — mints a fresh ACTIVE key that the JWKS now publishes instead;</li>
 *   <li>the COMPROMISED key keeps its DB row (status COMPROMISED) under the widened CHECK constraint
 *       from migration {@code 16-keys.sql} (proves ddl-validate accepts the new enum value);</li>
 *   <li>the admin controller endpoints are gated by {@code hasAuthority('key.rotate')}.</li>
 * </ul>
 */
class KeyCompromiseIT extends AbstractIntegrationTest {

    @Autowired private KeyService keyService;
    @Autowired private SigningKeyRepository signingKeyRepository;

    @Test
    void markCompromised_excludesFromJwks_andGeneratesReplacement() throws Exception {
        // The bootstrap ApplicationReadyEvent guarantees at least one ACTIVE key exists.
        String compromisedKid = keyService.getActiveSigningKeyPair().kid();
        assertThat(jwksKids()).contains(compromisedKid);

        // Flag it compromised -> a replacement ACTIVE key is generated and returned.
        var replacement = keyService.markCompromised(compromisedKid);
        assertThat(replacement).isPresent();
        String newActiveKid = replacement.get().getKid();
        assertThat(newActiveKid).isNotEqualTo(compromisedKid);
        assertThat(replacement.get().getStatus()).isEqualTo(SigningKey.Status.ACTIVE);

        // The DB row persisted as COMPROMISED (widened CHECK allows it under ddl-auto=validate).
        SigningKey row = signingKeyRepository.findByKid(compromisedKid).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SigningKey.Status.COMPROMISED);

        // JWKS now excludes the compromised kid and publishes the replacement instead.
        List<String> kidsAfter = jwksKids();
        assertThat(kidsAfter).doesNotContain(compromisedKid);
        assertThat(kidsAfter).contains(newActiveKid);

        // listPublishedKeys (the source for JWKS) likewise excludes the compromised key.
        assertThat(keyService.listPublishedKeys().stream().map(SigningKey::getKid).toList())
                .doesNotContain(compromisedKid)
                .contains(newActiveKid);

        // The new ACTIVE key is the one used for signing now.
        assertThat(keyService.getActiveSigningKeyPair().kid()).isEqualTo(newActiveKid);
    }

    @Test
    void markCompromised_isIdempotent_andDoesNotRegenerateOnRepeat() {
        String kid = keyService.getActiveSigningKeyPair().kid();

        var first = keyService.markCompromised(kid);
        assertThat(first).isPresent(); // it was ACTIVE -> replacement generated

        // A second call on the already-COMPROMISED kid must NOT mint another replacement.
        var second = keyService.markCompromised(kid);
        assertThat(second).isEmpty();
        assertThat(signingKeyRepository.findByKid(kid).orElseThrow().getStatus())
                .isEqualTo(SigningKey.Status.COMPROMISED);
    }

    // ------------------------------------------------------------------
    // Controller authorization
    // ------------------------------------------------------------------

    @Test
    void compromiseEndpoint_requiresKeyRotateAuthority() throws Exception {
        String kid = keyService.getActiveSigningKeyPair().kid();
        User actor = seedUser("key-actor-" + rnd() + "@example.com", "Key Actor", false);

        // Without key.rotate -> 403.
        mockMvc.perform(post("/api/v1/admin/keys/{kid}/compromise", kid)
                        .with(asUser(actor))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // With key.rotate -> 200 and a replacement is reported (the kid was ACTIVE).
        mockMvc.perform(post("/api/v1/admin/keys/{kid}/compromise", kid)
                        .with(asUser(actor, "key.rotate"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kid").value(kid))
                .andExpect(jsonPath("$.replacementGenerated").value(true))
                .andExpect(jsonPath("$.replacement.kid").exists());

        assertThat(jwksKids()).doesNotContain(kid);
    }

    @Test
    void rotateKekEndpoint_requiresKeyRotateAuthority_andReEncryptsRows() throws Exception {
        User actor = seedUser("kek-actor-" + rnd() + "@example.com", "Kek Actor", false);

        // Without authority -> 403.
        mockMvc.perform(post("/api/v1/admin/keys/rotate-kek")
                        .with(asUser(actor))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        long signingKeyCount = signingKeyRepository.count();
        MvcResult res = mockMvc.perform(post("/api/v1/admin/keys/rotate-kek")
                        .with(asUser(actor, "key.rotate"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        // KEK rotation now re-encrypts EVERY KeyEncryptor-protected column (signing keys + MFA/webhook/
        // SSO secrets), not just signing_keys, so the total is at least the signing-key row count (other
        // categories' rows, if any seeded by sibling tests, only add to it).
        assertThat(body.get("reEncrypted").asInt()).isGreaterThanOrEqualTo((int) signingKeyCount);

        // After KEK rotation the active key still loads/decrypts (round-trips through the new envelope).
        assertThat(keyService.getActiveSigningKeyPair().privateKey()).isNotNull();
    }

    /** Reads {@code /.well-known/jwks.json} and returns the list of published key ids. */
    private List<String> jwksKids() throws Exception {
        String json = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode keys = objectMapper.readTree(json).get("keys");
        List<String> kids = new ArrayList<>();
        if (keys != null) {
            for (JsonNode k : keys) {
                kids.add(k.get("kid").asText());
            }
        }
        return kids;
    }
}
