package com.example.cp.rbac;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Centralized RBAC privilege-escalation guard reused by {@code RbacController.assignRole}.
 *
 * <p>SecurityContext authorities are global-only (JwtAuthFilter computes authoritiesFor(userId, null)),
 * so org-scoped permissions are re-queried via {@link PermissionService} for the amplification check.
 */
@Service
public class RbacAuthorizationService {

    private final PermissionService permissionService;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

    public RbacAuthorizationService(PermissionService permissionService,
                                    RolePermissionRepository rolePermissionRepository,
                                    PermissionRepository permissionRepository) {
        this.permissionService = permissionService;
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * Validates that {@code actor} may assign {@code targetRole} (scoped to {@code orgId}; null = global)
     * to {@code targetUserId}. Throws {@link ApiException} (mapped to 401/403/400) on violation.
     */
    public void assertCanAssign(AuthenticatedUser actor, Role targetRole, UUID targetUserId, UUID orgId) {
        // (1) Authentication.
        if (actor == null) {
            throw ApiException.unauthorized("Not authenticated");
        }

        // (2) Block system / super roles via the API. The seeded SUPER_ADMIN and ORG_* roles are
        // is_system=TRUE; only non-system roles are assignable through this endpoint. This runs
        // BEFORE the superAdmin amplification bypass so even super admins cannot grant SUPER_ADMIN.
        if (targetRole == null) {
            throw ApiException.badRequest("Role is required");
        }
        if ("SUPER_ADMIN".equals(targetRole.getCode())) {
            throw ApiException.forbidden("SUPER_ADMIN cannot be assigned via the API");
        }
        if (targetRole.isSystem()) {
            throw ApiException.forbidden("System roles cannot be assigned via the API");
        }

        // (3) Scope authorization: global assignment requires platform authority. The @PreAuthorize
        // already enforces role.assign, but re-assert here because superAdmin bypasses hasAuthority
        // and the org-scoped path differs.
        if (orgId == null && !actor.superAdmin() && !actor.hasAuthority("role.assign")) {
            throw ApiException.forbidden("Global role assignment requires platform authority");
        }

        // (4) Privilege amplification: an actor cannot grant any permission code it does not itself
        // hold. Use org-scoped permissions (orgId may be null -> global perms), not the global-only
        // SecurityContext authorities.
        if (!actor.superAdmin()) {
            Set<String> actorPerms = permissionService.permissionsFor(actor.userId(), orgId);
            for (String code : permissionCodesForRole(targetRole.getId())) {
                if (!actorPerms.contains(code)) {
                    throw ApiException.forbidden("Cannot grant authority you do not hold: " + code);
                }
            }
        }

        // (5) Self-elevation guard: prevent assigning roles to oneself regardless of amplification
        // edge cases (superAdmin excluded).
        if (targetUserId != null && targetUserId.equals(actor.userId()) && !actor.superAdmin()) {
            throw ApiException.forbidden("Cannot assign roles to yourself");
        }
    }

    /** Resolves the set of permission codes granted by the role. Exposed for unit testing. */
    public Set<String> permissionCodesForRole(UUID roleId) {
        Set<String> codes = new LinkedHashSet<>();
        if (roleId == null) {
            return codes;
        }
        for (RolePermission rp : rolePermissionRepository.findByRoleId(roleId)) {
            permissionRepository.findById(rp.getPermissionId())
                    .ifPresent(p -> codes.add(p.getCode()));
        }
        return codes;
    }
}
