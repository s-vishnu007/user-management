package com.example.cp.rbac;

import com.example.cp.audit.AuditLogRepository;
import com.example.cp.audit.AuditOutcome;
import com.example.cp.common.Ids;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end guards for {@code DELETE /api/v1/rbac/users/{userId}/roles/{roleId}} (P3): role removal
 * now mirrors the system-role / global-scope / privilege-amplification guard that role assignment
 * already enforces, so removal cannot be used to side-step it.
 *
 * <p>Companion to {@code RbacPrivilegeEscalationIT} (assignment). Each denial is also asserted to
 * have produced a DENIED audit row (the controller sets {@code rbac.role.removed} before the guard).
 */
class RbacRoleRemovalGuardIT extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    private long deniedCount() {
        return auditLogRepository.findAll().stream()
                .filter(a -> a.getOutcome() == AuditOutcome.DENIED)
                .count();
    }

    private Role seedNonSystemRole(String code, String... permissionCodes) {
        Role role = roleRepository.save(Role.builder()
                .id(Ids.newId())
                .code(code)
                .name(code)
                .description(code)
                .isSystem(false)
                .build());
        for (String pc : permissionCodes) {
            Permission p = permissionRepository.findByCode(pc)
                    .orElseThrow(() -> new IllegalStateException("Seed permission not found: " + pc));
            rolePermissionRepository.save(RolePermission.builder()
                    .roleId(role.getId())
                    .permissionId(p.getId())
                    .build());
        }
        return role;
    }

    private String url(UUID userId, UUID roleId) {
        return "/api/v1/rbac/users/" + userId + "/roles/" + roleId;
    }

    // ------------------------------------------------------------------
    // System / SUPER_ADMIN roles cannot be removed via the API (even with role.assign).
    // ------------------------------------------------------------------

    @Test
    void actorWithRoleAssign_cannotRemoveSuperAdminRole() throws Exception {
        User actor = seedUser("remover-" + rnd() + "@example.com", "Remover", false);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);
        Role superAdmin = roleRepository.findByCode("SUPER_ADMIN").orElseThrow();
        grantRole(target.getId(), "SUPER_ADMIN", null);

        long before = deniedCount();

        mockMvc.perform(delete(url(target.getId(), superAdmin.getId()))
                        .with(asUser(actor, "role.assign")))
                .andExpect(status().isForbidden());

        assertThat(deniedCount()).isGreaterThan(before);
        assertThat(userRoleRepository.countAssignment(target.getId(), superAdmin.getId(), null))
                .as("the SUPER_ADMIN assignment must remain")
                .isEqualTo(1L);
    }

    @Test
    void actorWithRoleAssign_cannotRemoveSeededSystemRole() throws Exception {
        User actor = seedUser("remover-" + rnd() + "@example.com", "Remover", false);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);
        Role orgAdmin = roleRepository.findByCode("ORG_ADMIN").orElseThrow();
        grantRole(target.getId(), "ORG_ADMIN", null);

        long before = deniedCount();

        mockMvc.perform(delete(url(target.getId(), orgAdmin.getId()))
                        .with(asUser(actor, "role.assign")))
                .andExpect(status().isForbidden());

        assertThat(deniedCount()).isGreaterThan(before);
        assertThat(userRoleRepository.countAssignment(target.getId(), orgAdmin.getId(), null)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Privilege amplification: cannot remove a role granting a code the actor doesn't hold.
    // ------------------------------------------------------------------

    @Test
    void actorLackingGrantedPermission_cannotRemoveNonSystemRole_byAmplification() throws Exception {
        // Actor's only global role grants org.read/subscription.read/user.read (ORG_MEMBER) but NOT
        // user.write; they cannot remove a role conferring user.write.
        User actor = seedUser("limited-" + rnd() + "@example.com", "Limited", false);
        grantRole(actor.getId(), "ORG_MEMBER", null);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        Role customRole = seedNonSystemRole("custom-writer-" + rnd(), "user.write");
        grantRole(target.getId(), customRole.getCode(), null);

        long before = deniedCount();

        mockMvc.perform(delete(url(target.getId(), customRole.getId()))
                        .with(asUser(actor, "role.assign")))
                .andExpect(status().isForbidden());

        assertThat(deniedCount()).isGreaterThan(before);
        assertThat(userRoleRepository.countAssignment(target.getId(), customRole.getId(), null)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Legitimate removal: actor holds every code the (non-system) role grants.
    // ------------------------------------------------------------------

    @Test
    void platformActorHoldingGrantedCodes_removesNonSystemRole_succeeds() throws Exception {
        User actor = seedUser("platform-" + rnd() + "@example.com", "Platform", false);
        grantRole(actor.getId(), "ORG_OWNER", null); // ORG_OWNER's DB perms include subscription.read
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        Role customRole = seedNonSystemRole("custom-reader-" + rnd(), "subscription.read");
        grantRole(target.getId(), customRole.getCode(), null);

        mockMvc.perform(delete(url(target.getId(), customRole.getId()))
                        .with(asUser(actor, "role.assign", "subscription.read")))
                .andExpect(status().isNoContent());

        assertThat(userRoleRepository.countAssignment(target.getId(), customRole.getId(), null))
                .as("the non-system role assignment is removed")
                .isZero();
    }

    @Test
    void superAdmin_removesNonSystemRole_succeeds() throws Exception {
        User root = seedUser("root-" + rnd() + "@example.com", "Root", true);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        Role customRole = seedNonSystemRole("custom-x-" + rnd(), "subscription.read");
        grantRole(target.getId(), customRole.getCode(), null);

        // A real super-admin principal carries every permission code (AuthoritiesLoader expands them),
        // including role.assign, so the @PreAuthorize gate passes and assertCanRemove's super-admin
        // bypass skips the amplification lookup.
        mockMvc.perform(delete(url(target.getId(), customRole.getId()))
                        .with(asUser(root.getId(), root.getEmail(), true, "role.assign")))
                .andExpect(status().isNoContent());

        assertThat(userRoleRepository.countAssignment(target.getId(), customRole.getId(), null)).isZero();
    }
}
