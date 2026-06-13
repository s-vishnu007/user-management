package com.example.cp.rbac;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side page/size caps on the RBAC catalog endpoints (P3). These now use real Spring Data
 * pagination ({@code findAll(Pageable)}) rather than fabricating a {@link com.example.cp.common.PagedResponse}
 * over {@code findAll()}. Asserts the window is honored and an over-large {@code size} is clamped to
 * {@code PageRequestParams.MAX_SIZE}. Totals are not asserted exactly (the suite is non-transactional
 * and other tests seed roles/permissions), only the window/size invariants.
 */
class RbacListPaginationIT extends AbstractIntegrationTest {

    @Test
    void listRoles_appliesWindow() throws Exception {
        User actor = seedUser("rbac-reader-" + rnd() + "@example.com", "Reader", false);

        mockMvc.perform(get("/api/v1/rbac/roles")
                        .with(asUser(actor, "rbac.read"))
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", lessThanOrEqualTo(2)))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void listRoles_clampsOversizeToMaxSize() throws Exception {
        User actor = seedUser("rbac-reader-" + rnd() + "@example.com", "Reader", false);

        mockMvc.perform(get("/api/v1/rbac/roles")
                        .with(asUser(actor, "rbac.read"))
                        .param("size", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.items.length()", lessThanOrEqualTo(200)));
    }

    @Test
    void listPermissions_appliesWindow() throws Exception {
        User actor = seedUser("rbac-reader-" + rnd() + "@example.com", "Reader", false);

        mockMvc.perform(get("/api/v1/rbac/permissions")
                        .with(asUser(actor, "rbac.read"))
                        .param("page", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", lessThanOrEqualTo(3)))
                .andExpect(jsonPath("$.size").value(3));
    }
}
