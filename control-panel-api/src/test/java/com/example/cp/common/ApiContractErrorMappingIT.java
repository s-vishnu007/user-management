package com.example.cp.common;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage that standard client errors map to their correct HTTP status (NOT 500) now that
 * {@link GlobalExceptionHandler} extends {@code ResponseEntityExceptionHandler}. Pre-fix these all
 * fell through the catch-all {@code @ExceptionHandler(Exception.class)} and returned 500, polluting
 * 5xx SLOs and contradicting the OpenAPI contract.
 *
 * <p>Exercised against a real authorised mutating endpoint
 * ({@code POST /api/v1/orgs/{orgId}/api-keys}) so the request reaches the dispatcher.</p>
 */
class ApiContractErrorMappingIT extends com.example.cp.support.AbstractIntegrationTest {

    private record Caller(Organization org, User owner) {}

    private Caller owner() {
        Organization org = seedOrg("Contract Org");
        User u = seedUser("contract-" + rnd() + "@example.com", "Contract Owner", false);
        addOrgMember(org.getId(), u.getId(), OrgMember.Role.OWNER);
        return new Caller(org, u);
    }

    private String apiKeysPath(UUID orgId) {
        return "/api/v1/orgs/" + orgId + "/api-keys";
    }

    @Test
    void malformedJsonBody_returns400_not500() throws Exception {
        Caller c = owner();
        mockMvc.perform(post(apiKeysPath(c.org().getId()))
                        .with(asUser(c.owner()))
                        .contentType("application/json")
                        .content("{ this is not valid json "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongContentType_returns415_not500() throws Exception {
        Caller c = owner();
        mockMvc.perform(post(apiKeysPath(c.org().getId()))
                        .with(asUser(c.owner()))
                        .contentType("text/plain")
                        .content("name=foo"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void wrongHttpMethod_returns405_not500() throws Exception {
        Caller c = owner();
        // The api-keys collection supports GET/POST but not DELETE on the collection path.
        mockMvc.perform(delete(apiKeysPath(c.org().getId()))
                        .with(asUser(c.owner())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void typeMismatchedUuidPathVar_returns400_not500() throws Exception {
        Caller c = owner();
        // "not-a-uuid" cannot bind to the {orgId} UUID path variable.
        mockMvc.perform(get("/api/v1/orgs/not-a-uuid/api-keys")
                        .with(asUser(c.owner())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSubpath_returns404_not500() throws Exception {
        Caller c = owner();
        mockMvc.perform(get("/api/v1/this-endpoint-does-not-exist")
                        .with(asUser(c.owner())))
                .andExpect(status().isNotFound());
    }
}
