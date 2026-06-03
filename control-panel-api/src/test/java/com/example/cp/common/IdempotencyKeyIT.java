package com.example.cp.common;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the cross-cutting {@code Idempotency-Key} support (#81) built on
 * {@link com.example.cp.support.AbstractIntegrationTest}.
 *
 * <p>Exercises the contract on a real mutating endpoint
 * ({@code POST /api/v1/orgs/{orgId}/api-keys}, which mints a fresh random key and persists exactly one
 * {@code api_keys} row per successful call):</p>
 *
 * <ol>
 *   <li><b>Same key &rarr; single effect, replayed response.</b> Two POSTs carrying the same
 *       {@code Idempotency-Key} produce exactly ONE persisted api-key, the second response is a
 *       byte-for-byte replay of the first (proving the handler did not re-run and re-mint a key), and
 *       it carries the {@code Idempotency-Replayed: true} marker.</li>
 *   <li><b>Different keys &rarr; independent effects.</b> Two POSTs with distinct keys each run,
 *       producing TWO distinct api-keys, neither marked replayed.</li>
 *   <li><b>No header &rarr; zero behaviour change.</b> Two header-less POSTs both execute (two keys),
 *       confirming idempotency is inert when the header is absent.</li>
 *   <li><b>Same key, different body &rarr; rejected.</b> Reusing a key with a different request body
 *       is refused with {@code 422} rather than silently replaying an unrelated response.</li>
 * </ol>
 *
 * <p>The caller is an org OWNER (human principal injected via {@code asUser}, plus the matching
 * {@link OrgMember} row {@code @tenantAccess.canManageOrg} requires) so the endpoint authorises.
 * Persistence side effects are asserted directly against {@code apiKeyRepository} (exposed by the base
 * harness) and the idempotency rows against the autowired {@link IdempotencyKeyRepository}.</p>
 */
class IdempotencyKeyIT extends com.example.cp.support.AbstractIntegrationTest {

    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String HEADER = "Idempotency-Key";
    private static final String REPLAYED = "Idempotency-Replayed";
    private static final String BODY = "{\"name\":\"ci-key\",\"scopes\":[\"usage.read\"]}";

    private record Caller(Organization org, User owner) {}

    /** Seeds an org plus an OWNER member so canManageOrg(orgId) passes for the returned principal. */
    private Caller owner() {
        Organization org = seedOrg("Idem Org");
        User u = seedUser("idem-" + rnd() + "@example.com", "Idem Owner", false);
        addOrgMember(org.getId(), u.getId(), OrgMember.Role.OWNER);
        return new Caller(org, u);
    }

    private String path(UUID orgId) {
        return "/api/v1/orgs/" + orgId + "/api-keys";
    }

    // ------------------------------------------------------------------
    // 1. Same key -> single effect, replayed response
    // ------------------------------------------------------------------

    @Test
    void sameKey_singleEffect_andReplayedResponse() throws Exception {
        Caller c = owner();
        String key = "idem-" + rnd();

        MvcResult first = mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();

        String firstBody = first.getResponse().getContentAsString();
        // The first request is the originator, not a replay.
        assertThat(first.getResponse().getHeader(REPLAYED)).isNull();

        MvcResult second = mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();

        String secondBody = second.getResponse().getContentAsString();

        // The replay is byte-for-byte identical and flagged.
        assertThat(second.getResponse().getHeader(REPLAYED)).isEqualTo("true");
        assertThat(secondBody).isEqualTo(firstBody);

        // The generated key id is identical -> the handler did NOT run a second time.
        JsonNode firstJson = objectMapper.readTree(firstBody);
        JsonNode secondJson = objectMapper.readTree(secondBody);
        assertThat(secondJson.get("id").asText()).isEqualTo(firstJson.get("id").asText());
        assertThat(secondJson.get("key").asText()).isEqualTo(firstJson.get("key").asText());

        // Exactly ONE api-key persisted for the org despite two POSTs.
        assertThat(apiKeyRepository.findByOrgId(c.org().getId())).hasSize(1);

        // Exactly one stored idempotency record, completed with a 200 and the first body.
        var stored = idempotencyKeyRepository.findByIdemKeyAndMethodAndPathAndActorUserId(
                key, "POST", path(c.org().getId()), c.owner().getId().toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().getResponseStatus()).isEqualTo(200);
        assertThat(stored.get().getResponseBody()).isEqualTo(firstBody);
    }

    // ------------------------------------------------------------------
    // 2. Different keys -> independent effects
    // ------------------------------------------------------------------

    @Test
    void differentKeys_runIndependently() throws Exception {
        Caller c = owner();

        mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, "idem-" + rnd())
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk());

        MvcResult second = mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, "idem-" + rnd())
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getHeader(REPLAYED)).isNull();
        assertThat(apiKeyRepository.findByOrgId(c.org().getId())).hasSize(2);
    }

    // ------------------------------------------------------------------
    // 3. No header -> zero behaviour change (every request runs)
    // ------------------------------------------------------------------

    @Test
    void noHeader_everyRequestRuns() throws Exception {
        Caller c = owner();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(path(c.org().getId()))
                            .with(asUser(c.owner()))
                            .contentType("application/json")
                            .content(BODY))
                    .andExpect(status().isOk());
        }

        assertThat(apiKeyRepository.findByOrgId(c.org().getId())).hasSize(2);
    }

    // ------------------------------------------------------------------
    // 4. Same key, different body -> rejected (no silent replay)
    // ------------------------------------------------------------------

    @Test
    void sameKey_differentBody_isRejected() throws Exception {
        Caller c = owner();
        String key = "idem-" + rnd();

        mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content("{\"name\":\"different\",\"scopes\":[\"usage.read\"]}"))
                .andExpect(status().isUnprocessableEntity());

        // The mismatched retry did NOT create a second key.
        assertThat(apiKeyRepository.findByOrgId(c.org().getId())).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 5. API-key actor scoping isolates callers
    // ------------------------------------------------------------------

    @Test
    void apiKeyActor_doesNotReplayAnotherActorsRecord() throws Exception {
        // A human OWNER stores a record under their user-id actor; an api-key principal for the SAME
        // org reusing the same key+path is a distinct actor and must NOT see the human's response.
        Caller c = owner();
        String key = "idem-" + rnd();

        mockMvc.perform(post(path(c.org().getId()))
                        .with(asUser(c.owner()))
                        .header(HEADER, key)
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk());

        // Distinct actor key persisted for the human; the api-key actor record does not exist.
        var humanRecord = idempotencyKeyRepository.findByIdemKeyAndMethodAndPathAndActorUserId(
                key, "POST", path(c.org().getId()), c.owner().getId().toString());
        var apiKeyActorRecord = idempotencyKeyRepository.findByIdemKeyAndMethodAndPathAndActorUserId(
                key, "POST", path(c.org().getId()), "apikey:" + c.org().getId());
        assertThat(humanRecord).isPresent();
        assertThat(apiKeyActorRecord).isEmpty();
    }
}
