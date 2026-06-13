package com.example.cp.plans;

import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for {@code POST /api/v1/plans/{id}/permissions} (P0-2).
 *
 * <p>Before the fix the admin UI posted {@code {"permissions":[...]}} but the backend DTO field is
 * {@code permissionCodes}; Jackson ignored the unknown property, {@code permissionCodes} bound to
 * {@code null}, and {@code replacePermissions} ran {@code deleteByIdPlanId} then inserted nothing —
 * silently wiping every entitlement permission with a 200. These tests prove:
 * <ol>
 *   <li>the UI's historical {@code {"permissions":[...]}} body now binds correctly (via
 *       {@code @JsonAlias}) and is persisted rather than wiping the plan;</li>
 *   <li>a null/absent list is REJECTED with 400 (and the existing permissions are NOT wiped);</li>
 *   <li>the canonical {@code {"permissionCodes":[...]}} body still works;</li>
 *   <li>an explicit empty array is a deliberate "clear all" that succeeds.</li>
 * </ol>
 */
class PlanPermissionsContractIT extends AbstractIntegrationTest {

    @Autowired
    private PlanPermissionRepository planPermissionRepository;

    private String url(UUID planId) {
        return "/api/v1/plans/" + planId + "/permissions";
    }

    private void seedExistingPermissions(UUID planId, String... codes) {
        for (String c : codes) {
            planPermissionRepository.save(PlanPermission.builder()
                    .id(PlanPermission.Pk.builder().planId(planId).permissionCode(c).build())
                    .build());
        }
    }

    private List<String> codesOf(UUID planId) {
        return planPermissionRepository.findByIdPlanId(planId).stream()
                .map(pp -> pp.getId().getPermissionCode())
                .sorted()
                .toList();
    }

    // ------------------------------------------------------------------
    // 1. The admin-UI body shape {"permissions":[...]} now binds via @JsonAlias.
    // ------------------------------------------------------------------

    @Test
    void uiPermissionsFieldName_bindsAndReplaces_doesNotWipe() throws Exception {
        Plan plan = seedNewPlan("contract-ui-" + rnd(), 365);
        seedExistingPermissions(plan.getId(), "old.perm");

        mockMvc.perform(post(url(plan.getId()))
                        .with(asUser(UUID.randomUUID(), "admin@example.com", false, "plan.write"))
                        .contentType("application/json")
                        .content("{\"permissions\":[\"feature.read\",\"feature.write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.containsInAnyOrder("feature.read", "feature.write")));

        assertThat(codesOf(plan.getId()))
                .as("the UI body shape is honored and the plan is updated, NOT wiped")
                .containsExactly("feature.read", "feature.write");
    }

    // ------------------------------------------------------------------
    // 2. A null / absent list is rejected with 400 and does NOT wipe existing permissions.
    // ------------------------------------------------------------------

    @Test
    void nullPermissionList_isRejected400_andDoesNotWipe() throws Exception {
        Plan plan = seedNewPlan("contract-null-" + rnd(), 365);
        seedExistingPermissions(plan.getId(), "keep.this", "and.this");

        // Body with neither permissionCodes nor permissions -> permissionCodes binds null.
        mockMvc.perform(post(url(plan.getId()))
                        .with(asUser(UUID.randomUUID(), "admin@example.com", false, "plan.write"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(codesOf(plan.getId()))
                .as("the absent-list request must NOT delete existing entitlement permissions (P0-2)")
                .containsExactly("and.this", "keep.this");
    }

    @Test
    void explicitNullPermissionCodes_isRejected400_andDoesNotWipe() throws Exception {
        Plan plan = seedNewPlan("contract-explicit-null-" + rnd(), 365);
        seedExistingPermissions(plan.getId(), "keep.me");

        mockMvc.perform(post(url(plan.getId()))
                        .with(asUser(UUID.randomUUID(), "admin@example.com", false, "plan.write"))
                        .contentType("application/json")
                        .content("{\"permissionCodes\":null}"))
                .andExpect(status().isBadRequest());

        assertThat(codesOf(plan.getId())).containsExactly("keep.me");
    }

    // ------------------------------------------------------------------
    // 3. The canonical permissionCodes field still works.
    // ------------------------------------------------------------------

    @Test
    void canonicalPermissionCodesField_stillWorks() throws Exception {
        Plan plan = seedNewPlan("contract-canon-" + rnd(), 365);

        mockMvc.perform(post(url(plan.getId()))
                        .with(asUser(UUID.randomUUID(), "admin@example.com", false, "plan.write"))
                        .contentType("application/json")
                        .content("{\"permissionCodes\":[\"alpha.read\"]}"))
                .andExpect(status().isOk());

        assertThat(codesOf(plan.getId())).containsExactly("alpha.read");
    }

    // ------------------------------------------------------------------
    // 4. An explicit empty array is a deliberate "clear all" -> 200, all removed.
    // ------------------------------------------------------------------

    @Test
    void explicitEmptyArray_clearsAllPermissions() throws Exception {
        Plan plan = seedNewPlan("contract-empty-" + rnd(), 365);
        seedExistingPermissions(plan.getId(), "to.be.cleared");

        mockMvc.perform(post(url(plan.getId()))
                        .with(asUser(UUID.randomUUID(), "admin@example.com", false, "plan.write"))
                        .contentType("application/json")
                        .content("{\"permissionCodes\":[]}"))
                .andExpect(status().isOk());

        assertThat(codesOf(plan.getId()))
                .as("an explicit empty array is a legitimate clear-all and is allowed")
                .isEmpty();
    }
}
