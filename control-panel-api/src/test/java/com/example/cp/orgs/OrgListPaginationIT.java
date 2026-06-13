package com.example.cp.orgs;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side page/size caps + honest {@link com.example.cp.common.PagedResponse} envelopes on the
 * org list endpoints (P3). Proves {@code GET /orgs/{orgId}/members} applies a real window, reports
 * the true total, and clamps an over-large {@code size} to {@code PageRequestParams.MAX_SIZE}.
 */
class OrgListPaginationIT extends AbstractIntegrationTest {

    @Test
    void listMembers_appliesWindow_andReportsTrueTotal() throws Exception {
        Organization org = seedOrg("Page Org");
        User owner = seedUser("owner-" + rnd() + "@example.com", "Owner", false);
        addOrgMember(org.getId(), owner.getId(), OrgMember.Role.OWNER);
        for (int i = 0; i < 5; i++) {
            User m = seedUser("m" + i + "-" + rnd() + "@example.com", "M" + i, false);
            addOrgMember(org.getId(), m.getId(), OrgMember.Role.MEMBER);
        }
        // total members = 6 (1 owner + 5 members)

        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asUser(owner, "org.read"))
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void listMembers_clampsOversizeToMaxSize() throws Exception {
        Organization org = seedOrg("Clamp Org");
        User owner = seedUser("owner-" + rnd() + "@example.com", "Owner", false);
        addOrgMember(org.getId(), owner.getId(), OrgMember.Role.OWNER);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asUser(owner, "org.read"))
                        .param("size", "100000"))
                .andExpect(status().isOk())
                // PageRequestParams.MAX_SIZE == 200
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void listMembers_secondPage_returnsRemainder() throws Exception {
        Organization org = seedOrg("Remainder Org");
        User owner = seedUser("owner-" + rnd() + "@example.com", "Owner", false);
        addOrgMember(org.getId(), owner.getId(), OrgMember.Role.OWNER);
        for (int i = 0; i < 3; i++) {
            User m = seedUser("m" + i + "-" + rnd() + "@example.com", "M" + i, false);
            addOrgMember(org.getId(), m.getId(), OrgMember.Role.MEMBER);
        }
        // total = 4

        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asUser(owner, "org.read"))
                        .param("page", "1")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(4));
    }
}
