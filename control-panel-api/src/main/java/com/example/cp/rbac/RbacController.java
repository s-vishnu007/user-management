package com.example.cp.rbac;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rbac")
public class RbacController {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;

    public RbacController(RoleRepository roleRepository,
                          PermissionRepository permissionRepository,
                          UserRoleRepository userRoleRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @GetMapping("/roles")
    public PagedResponse<RoleDto> listRoles() {
        List<RoleDto> items = roleRepository.findAll().stream().map(RoleDto::from).toList();
        return PagedResponse.of(items, items.size(), 0, items.size());
    }

    @GetMapping("/permissions")
    public PagedResponse<PermissionDto> listPermissions() {
        List<PermissionDto> items = permissionRepository.findAll().stream().map(PermissionDto::from).toList();
        return PagedResponse.of(items, items.size(), 0, items.size());
    }

    @PostMapping("/users/{userId}/roles")
    @PreAuthorize("hasAuthority('user.write')")
    @Transactional
    public ResponseEntity<UserRoleDto> assignRole(@PathVariable UUID userId,
                                                  @Valid @RequestBody AssignRoleRequest body) {
        Role role = roleRepository.findByCode(body.roleCode())
                .orElseThrow(() -> ApiException.notFound("Role not found"));
        if (userRoleRepository.countAssignment(userId, role.getId(), body.orgId()) > 0) {
            throw ApiException.conflict("Role already assigned");
        }
        UserRole ur = UserRole.builder()
                .userId(userId)
                .roleId(role.getId())
                .orgId(body.orgId())
                .build();
        userRoleRepository.save(ur);
        AuditContext.set("rbac.role.assigned");
        AuditContext.setTarget("user_role", userId + ":" + role.getId() + (body.orgId() == null ? "" : ":" + body.orgId()));
        AuditContext.putPayload("role_code", role.getCode());
        return ResponseEntity.status(201).body(new UserRoleDto(userId, role.getId(), role.getCode(), body.orgId()));
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('user.write')")
    @Transactional
    public ResponseEntity<Void> removeRole(@PathVariable UUID userId,
                                           @PathVariable UUID roleId,
                                           @org.springframework.web.bind.annotation.RequestParam(value = "orgId", required = false) UUID orgId) {
        int removed = userRoleRepository.deleteAssignment(userId, roleId, orgId);
        if (removed == 0) {
            throw ApiException.notFound("Role assignment not found");
        }
        AuditContext.set("rbac.role.removed");
        AuditContext.setTarget("user_role", userId + ":" + roleId + (orgId == null ? "" : ":" + orgId));
        return ResponseEntity.noContent().build();
    }

    public record AssignRoleRequest(@NotBlank String roleCode, UUID orgId) {}

    public record RoleDto(UUID id, String code, String name, String description, boolean isSystem) {
        public static RoleDto from(Role r) {
            return new RoleDto(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isSystem());
        }
    }

    public record PermissionDto(UUID id, String code, String name, String description, String category) {
        public static PermissionDto from(Permission p) {
            return new PermissionDto(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getCategory());
        }
    }

    public record UserRoleDto(UUID userId, UUID roleId, String roleCode, UUID orgId) {}
}
