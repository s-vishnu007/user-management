package com.example.cp.common;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the corrected Idempotency-Key response semantics:
 *
 * <ol>
 *   <li><b>4xx is NOT cached / the claim is released.</b> A first request whose handler returns a 4xx
 *       client error must not pin that error to the key for the TTL. A corrected retry under the SAME
 *       key must re-execute and succeed, and no stored idempotency row may linger for the failed
 *       attempt.</li>
 *   <li><b>Replay restores Content-Type (and Location).</b> The captured response {@code Content-Type}
 *       is reproduced on the replay rather than blindly guessed, and the stored row records it.</li>
 * </ol>
 *
 * <p>Exercised against {@code POST /api/v1/orgs/{orgId}/api-keys}: a disallowed scope makes the
 * handler throw {@code ApiException.badRequest} (400), while a permitted scope succeeds (200).</p>
 */
class IdempotencyResponseSemanticsIT extends com.example.cp.support.AbstractIntegrationTest {

    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String HEADER = "Idempotency-Key";
    private static final String REPLAYED = "Idempotency-Replayed";
    private static final String VALID_BODY = "{\"name\":\"ci-key\",\"scopes\":[\"usage.read\"]}";
    private static final String BAD_SCOPE_BODY = "{\"name\":\"ci-key\",\"scopes\":[\"subscription.write\"]}";

    private record Caller(Organization org, User owner) {}

    private Caller owner() {
        Organization org = seedOrg("Idem Sem Org");
        User u = seedUser("idemsem-" + rnd() + "@example.com", "Idem Sem Owner", false);
        addOrgMember(org.getId(), u.getId(), OrgMember.Role.OWNER);
        return new Caller(org, u);
    }

    private String path(UUID orgId) {
        return "/api/v1/orgs/" + orgId + "/api-keys";
    }

    @Test
    void clientError_isNotCached_andCorrectedRetryReExecutes() throws Exception {
        Caller c = owner();
        String key = "idem-" + rnd();

        // First call returns 400 (disallowed scope). Its claim must be RELEASED, not cached.
        mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(BAD_SCOPE_BODY))
                .andExpect(status().isBadRequest());

        // No idempotency row should remain for the failed attempt (claim released).
        assertThat(idempotencyKeyRepository.findByIdemKeyAndMethodAndPathAndActorUserId(
                key, "POST", path(c.org().getId()), c.owner().getId().toString())).isEmpty();

        // A corrected retry under the SAME key re-executes (NOT a replay of the 400) and succeeds.
        MvcResult corrected = mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(corrected.getResponse().getHeader(REPLAYED)).isNull();

        // Exactly one api-key was created (only the corrected call ran the side effect).
        assertThat(apiKeyRepository.findByOrgId(c.org().getId())).hasSize(1);
        // The successful outcome is now stored and carries the captured content type.
        var stored = idempotencyKeyRepository.findByIdemKeyAndMethodAndPathAndActorUserId(
                key, "POST", path(c.org().getId()), c.owner().getId().toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().getResponseStatus()).isEqualTo(200);
        assertThat(stored.get().getResponseContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void replay_restoresCapturedContentType() throws Exception {
        Caller c = owner();
        String key = "idem-" + rnd();

        mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        MvcResult replay = mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getHeader(REPLAYED)).isEqualTo("true");
        // The replay reproduces the originally-captured Content-Type.
        assertThat(replay.getResponse().getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
    }
}
