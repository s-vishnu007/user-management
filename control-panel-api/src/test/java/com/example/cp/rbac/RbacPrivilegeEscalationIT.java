package com.example.cp.rbac;

import com.example.cp.audit.AuditLog;
import com.example.cp.audit.AuditLogRepository;
import com.example.cp.audit.AuditOutcome;
import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC privilege-escalation guard, end-to-end through the real HTTP stack on
 * {@link AbstractIntegrationTest}.
 *
 * <p>Proves the layered defence in {@code RbacController.assignRole} /
 * {@link RbacAuthorizationService#assertCanAssign} and {@code OrgController.addMember} /
 * {@code OrgService.addMember}:
 * <ol>
 *   <li>The coarse {@code @PreAuthorize("hasAuthority('role.assign')")} gate: a user holding only
 *       {@code user.write} (the authority an ORG_ADMIN carries for user mutation) can NOT reach the
 *       role-assignment endpoint at all.</li>
 *   <li>Even WITH {@code role.assign}, the system-role block rejects {@code SUPER_ADMIN} and any
 *       {@code is_system=TRUE} role (ORG_OWNER/ORG_ADMIN/ORG_MEMBER/VIEWER) BEFORE any write and
 *       BEFORE the super-admin amplification bypass.</li>
 *   <li>Org membership escalation: an ADMIN of an org can NOT add an OWNER member (rank guard in
 *       {@code OrgService.addMember}).</li>
 *   <li>The legitimate path: a platform admin holding {@code role.assign} whose own (DB-resolved)
 *       permissions cover everything a fresh NON-system role grants may assign that role — 201, and
 *       the assignment is persisted with a {@code rbac.role.assigned} SUCCESS audit row.</li>
 * </ol>
 *
 * <p>Each denial is also checked to have produced a {@link AuditOutcome#DENIED} audit row. Because a
 * {@code @PreAuthorize} short-circuit (case 1) is recorded by {@code GlobalExceptionHandler} with a
 * possibly-null actor while an in-controller {@code ApiException} 403 (cases 2/3/4) is recorded by
 * the {@code AuditInterceptor}, the assertions use a before/after delta over DENIED-outcome rows
 * rather than depending on the exact action string or actor population of any single writer.
 */
class RbacPrivilegeEscalationIT extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    private long deniedCount() {
        return auditLogRepository.findAll().stream()
                .filter(a -> a.getOutcome() == AuditOutcome.DENIED)
                .count();
    }

    private boolean hasSuccessRow(String action, String targetType, String targetId) {
        return auditLogRepository.findAll().stream()
                .anyMatch(a -> a.getOutcome() == AuditOutcome.SUCCESS
                        && action.equals(a.getAction())
                        && targetType.equals(a.getTargetType())
                        && targetId.equals(a.getTargetId()));
    }

    /** Creates a NON-system role granting exactly the given permission codes. */
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

    private String assignBody(String roleCode, UUID orgId) throws Exception {
        return objectMapper.writeValueAsString(new AssignBody(roleCode, orgId));
    }

    // ------------------------------------------------------------------
    // 1. user.write alone cannot reach the role-assignment endpoint.
    // ------------------------------------------------------------------

    @Test
    void orgAdminWithOnlyUserWrite_cannotAssignSuperAdmin_blockedAtPreAuthorizeGate() throws Exception {
        User actor = seedUser("orgadmin-" + rnd() + "@example.com", "Org Admin", false);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        long before = deniedCount();

        // Holds user.write (what ORG_ADMIN carries for user mutation) but NOT role.assign.
        mockMvc.perform(post("/api/v1/rbac/users/{userId}/roles", target.getId())
                        .with(asUser(actor, "user.write", "user.read", "org.read"))
                        .contentType("application/json")
                        .content(assignBody("SUPER_ADMIN", null)))
                .andExpect(status().isForbidden());

        assertThat(deniedCount())
                .as("a DENIED audit row is written for the @PreAuthorize role.assign refusal")
                .isGreaterThan(before);

        // No assignment was created.
        Role superAdmin = roleRepository.findByCode("SUPER_ADMIN").orElseThrow();
        assertThat(userRoleRepository.countAssignment(target.getId(), superAdmin.getId(), null)).isZero();
    }

    // ------------------------------------------------------------------
    // 2. Even WITH role.assign, SUPER_ADMIN cannot be assigned via the API.
    // ------------------------------------------------------------------

    @Test
    void actorWithRoleAssign_cannotAssignSuperAdminRole() throws Exception {
        User actor = seedUser("assigner-" + rnd() + "@example.com", "Assigner", false);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        long before = deniedCount();

        mockMvc.perform(post("/api/v1/rbac/users/{userId}/roles", target.getId())
                        .with(asUser(actor, "role.assign"))
                        .contentType("application/json")
                        .content(assignBody("SUPER_ADMIN", null)))
                .andExpect(status().isForbidden());

        assertThat(deniedCount())
                .as("SUPER_ADMIN assignment is audited as DENIED")
                .isGreaterThan(before);

        Role superAdmin = roleRepository.findByCode("SUPER_ADMIN").orElseThrow();
        assertThat(userRoleRepository.countAssignment(target.getId(), superAdmin.getId(), null)).isZero();
    }

    // ------------------------------------------------------------------
    // 3. Even WITH role.assign, any is_system role is forbidden.
    // ------------------------------------------------------------------

    @Test
    void actorWithRoleAssign_cannotAssignSeededSystemRole() throws Exception {
        User actor = seedUser("assigner-" + rnd() + "@example.com", "Assigner", false);
        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        long before = deniedCount();

        // ORG_ADMIN is is_system=TRUE (seeded by changeset 02) -> not assignable via the API.
        mockMvc.perform(post("/api/v1/rbac/users/{userId}/roles", target.getId())
                        .with(asUser(actor, "role.assign"))
                        .contentType("application/json")
                        .content(assignBody("ORG_ADMIN", null)))
                .andExpect(status().isForbidden());

        assertThat(deniedCount())
                .as("assigning a system role is audited as DENIED")
                .isGreaterThan(before);

        Role orgAdmin = roleRepository.findByCode("ORG_ADMIN").orElseThrow();
        assertThat(userRoleRepository.countAssignment(target.getId(), orgAdmin.getId(), null)).isZero();
    }

    // ------------------------------------------------------------------
    // 4. Org-membership escalation: an ADMIN cannot add an OWNER member.
    // ------------------------------------------------------------------

    @Test
    void orgAdmin_cannotAddOwnerMember_isForbidden() throws Exception {
        Organization org = seedOrg("Esc Org");

        User admin = seedUser("admin-" + rnd() + "@example.com", "Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);
        grantRole(admin.getId(), "ORG_ADMIN", org.getId());

        // Existing user to be (illegitimately) promoted to OWNER.
        User newcomer = seedUser("newcomer-" + rnd() + "@example.com", "Newcomer", false);

        long before = deniedCount();

        String body = objectMapper.writeValueAsString(new AddMemberBody(newcomer.getEmail(), "OWNER"));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asUser(admin, "org.read", "user.invite"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());

        assertThat(deniedCount())
                .as("an ADMIN granting OWNER is audited as DENIED")
                .isGreaterThan(before);

        // The newcomer was NOT added to the org.
        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), newcomer.getId())).isEmpty();
    }

    /**
     * Control case for #4: the same ADMIN may add an equal-or-lower-rank member (MEMBER), proving the
     * denial above is specifically the OWNER-outranks-ADMIN guard and not a blanket block.
     */
    @Test
    void orgAdmin_canAddMemberRankMember_succeeds() throws Exception {
        Organization org = seedOrg("Ok Org");

        User admin = seedUser("admin-" + rnd() + "@example.com", "Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);
        grantRole(admin.getId(), "ORG_ADMIN", org.getId());

        User newcomer = seedUser("member-" + rnd() + "@example.com", "Member", false);

        String body = objectMapper.writeValueAsString(new AddMemberBody(newcomer.getEmail(), "MEMBER"));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asUser(admin, "org.read", "user.invite"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());

        OrgMember saved = orgMemberRepository.findByOrgIdAndUserId(org.getId(), newcomer.getId()).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(OrgMember.Role.MEMBER);
    }

    // ------------------------------------------------------------------
    // 5. Legitimate path: platform admin with role.assign assigns a NON-system role.
    // ------------------------------------------------------------------

    @Test
    void platformAdminWithRoleAssign_assignsNonSystemRole_succeeds() throws Exception {
        // Actor holds a GLOBAL ORG_OWNER assignment, whose DB-resolved permissions include
        // role.assign + subscription.read. The amplification check in assertCanAssign re-queries
        // permissionsFor(actor, orgId=null), so the actor genuinely holds everything the target role
        // grants.
        User actor = seedUser("platform-" + rnd() + "@example.com", "Platform Admin", false);
        grantRole(actor.getId(), "ORG_OWNER", null);

        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        // Fresh NON-system role granting only subscription.read (a code ORG_OWNER holds).
        Role customRole = seedNonSystemRole("custom-reader-" + rnd(), "subscription.read");

        mockMvc.perform(post("/api/v1/rbac/users/{userId}/roles", target.getId())
                        .with(asUser(actor, "role.assign", "subscription.read"))
                        .contentType("application/json")
                        .content(assignBody(customRole.getCode(), null)))
                .andExpect(status().isCreated());

        // Assignment persisted.
        assertThat(userRoleRepository.countAssignment(target.getId(), customRole.getId(), null))
                .as("the non-system role is assigned to the target")
                .isEqualTo(1L);

        // SUCCESS audit row with the controller's explicit action + target.
        String targetId = target.getId() + ":" + customRole.getId();
        assertThat(hasSuccessRow("rbac.role.assigned", "user_role", targetId))
                .as("a rbac.role.assigned SUCCESS audit row is written")
                .isTrue();

        // Sanity: no DENIED row was produced for this successful assignment's target.
        List<AuditLog> deniedForTarget = auditLogRepository.findAll().stream()
                .filter(a -> a.getOutcome() == AuditOutcome.DENIED)
                .filter(a -> targetId.equals(a.getTargetId()))
                .toList();
        assertThat(deniedForTarget).isEmpty();
    }

    /**
     * Negative twin of #5: a non-super actor whose DB permissions do NOT cover a code the target role
     * grants is blocked by the privilege-amplification check even though they hold role.assign.
     */
    @Test
    void actorWithRoleAssign_butLackingAGrantedPermission_isForbiddenByAmplificationCheck() throws Exception {
        // Actor's only global role grants org.read + subscription.read + user.read (ORG_MEMBER),
        // but NOT user.write. They cannot grant a role that confers user.write.
        User actor = seedUser("limited-" + rnd() + "@example.com", "Limited", false);
        grantRole(actor.getId(), "ORG_MEMBER", null);

        User target = seedUser("target-" + rnd() + "@example.com", "Target", false);

        Role customRole = seedNonSystemRole("custom-writer-" + rnd(), "user.write");

        long before = deniedCount();

        mockMvc.perform(post("/api/v1/rbac/users/{userId}/roles", target.getId())
                        // Inject role.assign so the @PreAuthorize gate passes and the amplification
                        // check (which uses DB perms, not these injected authorities) is what denies.
                        .with(asUser(actor, "role.assign"))
                        .contentType("application/json")
                        .content(assignBody(customRole.getCode(), null)))
                .andExpect(status().isForbidden());

        assertThat(deniedCount())
                .as("privilege-amplification denial is audited as DENIED")
                .isGreaterThan(before);

        assertThat(userRoleRepository.countAssignment(target.getId(), customRole.getId(), null)).isZero();
    }

    // ------------------------------------------------------------------
    // Request body DTOs (mirror the controller request records).
    // ------------------------------------------------------------------

    private record AssignBody(String roleCode, UUID orgId) {}

    private record AddMemberBody(String email, String role) {}
}
