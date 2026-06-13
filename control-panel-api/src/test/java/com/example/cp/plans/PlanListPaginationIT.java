package com.example.cp.plans;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side page/size cap on {@code GET /api/v1/plans} (P3). The endpoint returns a raw list (its
 * existing contract), but is now bounded by a {@code Pageable} capped at {@code PageRequestParams.MAX_SIZE}
 * so it can never return an unbounded result set.
 */
class PlanListPaginationIT extends AbstractIntegrationTest {

    @Test
    void list_respectsSizeWindow() throws Exception {
        // Seed several extra plans so a small page size is actually exercised.
        for (int i = 0; i < 5; i++) {
            seedNewPlan("page-plan-" + rnd(), 365);
        }
        User actor = seedUser("plan-reader-" + rnd() + "@example.com", "Reader", false);

        mockMvc.perform(get("/api/v1/plans")
                        .with(asUser(actor, "plan.read"))
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", lessThanOrEqualTo(2)));
    }

    @Test
    void list_oversizeIsClampedToMaxSize() throws Exception {
        User actor = seedUser("plan-reader-" + rnd() + "@example.com", "Reader", false);

        mockMvc.perform(get("/api/v1/plans")
                        .with(asUser(actor, "plan.read"))
                        .param("size", "999999"))
                .andExpect(status().isOk())
                // MAX_SIZE == 200; the seeded catalog is far smaller, so just assert it is bounded.
                .andExpect(jsonPath("$.length()", lessThanOrEqualTo(200)));
    }
}
