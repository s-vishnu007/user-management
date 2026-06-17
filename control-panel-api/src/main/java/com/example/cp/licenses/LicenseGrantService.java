package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.rbac.Permission;
import com.example.cp.rbac.PermissionRepository;
import com.example.cp.rbac.Role;
import com.example.cp.rbac.RolePermission;
import com.example.cp.rbac.RolePermissionRepository;
import com.example.cp.rbac.RoleRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates the per-user license flow: resolve the subject user (an existing org member, or
 * provision one from an email — "invite by email"), expand the chosen role presets + individual
 * permissions into a validated RBAC grant set, then hand off to {@link LicenseIssuer} to mint the
 * org-anchored token. Also exposes the catalog of grants the admin may pick from.
 *
 * <p>The whole call is transactional, so user provisioning, org membership and the issued token
 * commit (or roll back) together.
 */
@Service
public class LicenseGrantService {

    private final LicenseIssuer issuer;
    private final UserRepository userRepo;
    private final OrgMemberRepository memberRepo;
    private final RoleRepository roleRepo;
    private final PermissionRepository permissionRepo;
    private final RolePermissionRepository rolePermissionRepo;

    public LicenseGrantService(LicenseIssuer issuer,
                               UserRepository userRepo,
                               OrgMemberRepository memberRepo,
                               RoleRepository roleRepo,
                               PermissionRepository permissionRepo,
                               RolePermissionRepository rolePermissionRepo) {
        this.issuer = issuer;
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.roleRepo = roleRepo;
        this.permissionRepo = permissionRepo;
        this.rolePermissionRepo = rolePermissionRepo;
    }

    /**
     * Issues a per-user license for {@code orgId}. Exactly one of {@code userId} / {@code email} must
     * identify the subject; an unknown email provisions a new (password-less, unverified) user and
     * adds them to the org as MEMBER. The grant set is {@code expand(roleCodes) ∪ permissions},
     * validated against the RBAC catalog.
     */
    @Transactional
    public LicenseIssuer.IssuedLicense issue(UUID orgId,
                                             UUID userId,
                                             String email,
                                             List<String> roleCodes,
                                             List<String> permissions,
                                             Integer ttlDays,
                                             List<String> audience,
                                             boolean trial) {
        User subject = resolveSubject(orgId, userId, email);

        List<String> roleSnapshot = normalizeRoleCodes(roleCodes);
        List<String> resolvedPermissions = resolvePermissions(roleSnapshot, permissions);

        LicenseToken.LicenseType type = trial ? LicenseToken.LicenseType.TRIAL : LicenseToken.LicenseType.STANDARD;
        return issuer.issueForUser(orgId, subject.getId(), resolvedPermissions, roleSnapshot, ttlDays, audience, type);
    }

    /** Resolves (and if needed provisions) the subject user, guaranteeing org membership. */
    private User resolveSubject(UUID orgId, UUID userId, String email) {
        User subject;
        if (userId != null) {
            subject = userRepo.findById(userId)
                    .orElseThrow(() -> ApiException.notFound("User not found"));
        } else {
            String normalized = email == null ? "" : email.trim();
            if (normalized.isBlank()) {
                throw ApiException.badRequest("Provide a userId or an email to issue the license to");
            }
            subject = userRepo.findByEmail(normalized).orElseGet(() -> provisionUser(normalized));
        }
        ensureMember(orgId, subject.getId());
        return subject;
    }

    /** Creates a password-less, unverified user that exists purely as a license subject. */
    private User provisionUser(String email) {
        User u = User.builder()
                .id(Ids.newId())
                .email(email)
                .fullName(null)
                .status(User.Status.ACTIVE)
                .superAdmin(false)
                .emailVerified(false)
                .tokenVersion(0L)
                .createdAt(OffsetDateTime.now())
                .build();
        User saved = userRepo.save(u);
        AuditContext.putPayload("provisioned_user", saved.getId().toString());
        return saved;
    }

    /** Adds the subject to the org as MEMBER if not already a member (a license subject is always a member). */
    private void ensureMember(UUID orgId, UUID userId) {
        if (memberRepo.findByOrgIdAndUserId(orgId, userId).isPresent()) {
            return;
        }
        memberRepo.save(OrgMember.builder()
                .orgId(orgId)
                .userId(userId)
                .role(OrgMember.Role.MEMBER)
                .addedAt(OffsetDateTime.now())
                .build());
    }

    private List<String> normalizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String rc : roleCodes) {
            if (rc == null || rc.isBlank()) {
                continue;
            }
            roleRepo.findByCode(rc).orElseThrow(() -> ApiException.badRequest("Unknown role: " + rc));
            out.add(rc);
        }
        return new ArrayList<>(out);
    }

    /**
     * Resolves the final grant set. If the caller supplied an explicit {@code individualPermissions}
     * set it is AUTHORITATIVE (validated, deduped) and roles are treated as a snapshot only — this is
     * what the UI sends after the admin fine-tunes a role preset, so an unchecked permission stays
     * removed. If no explicit permissions are given, the chosen {@code roleCodes} are expanded via
     * {@code role_permissions} (a convenience for role-only / API callers). Every code is validated
     * against the RBAC catalog.
     */
    private List<String> resolvePermissions(List<String> roleCodes, List<String> individualPermissions) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        boolean hasExplicit = individualPermissions != null
                && individualPermissions.stream().anyMatch(s -> s != null && !s.isBlank());
        if (hasExplicit) {
            for (String pc : individualPermissions) {
                if (pc == null || pc.isBlank()) {
                    continue;
                }
                permissionRepo.findByCode(pc)
                        .orElseThrow(() -> ApiException.badRequest("Unknown permission: " + pc));
                resolved.add(pc);
            }
        } else {
            for (String rc : roleCodes) {
                Role role = roleRepo.findByCode(rc).orElseThrow(() -> ApiException.badRequest("Unknown role: " + rc));
                for (RolePermission rp : rolePermissionRepo.findByRoleId(role.getId())) {
                    permissionRepo.findById(rp.getPermissionId())
                            .map(Permission::getCode)
                            .ifPresent(resolved::add);
                }
            }
        }
        return new ArrayList<>(resolved);
    }

    /** The catalog of grants an admin may bake into a license: the permission catalog + roles with
     *  their expanded permission codes (so the UI can preset-then-fine-tune). */
    @Transactional(readOnly = true)
    public AssignableGrants assignableGrants() {
        List<GrantPermission> perms = permissionRepo.findAll().stream()
                .sorted(Comparator
                        .comparing((Permission p) -> p.getCategory() == null ? "" : p.getCategory())
                        .thenComparing(Permission::getCode))
                .map(p -> new GrantPermission(p.getCode(), p.getName(), p.getDescription(), p.getCategory()))
                .toList();

        List<GrantRole> roles = roleRepo.findAll().stream()
                .sorted(Comparator.comparing(Role::getCode))
                .map(r -> new GrantRole(
                        r.getCode(), r.getName(), r.getDescription(),
                        rolePermissionRepo.findByRoleId(r.getId()).stream()
                                .map(rp -> permissionRepo.findById(rp.getPermissionId())
                                        .map(Permission::getCode).orElse(null))
                                .filter(Objects::nonNull)
                                .sorted()
                                .toList()))
                .toList();

        return new AssignableGrants(perms, roles);
    }

    public record GrantPermission(String code, String name, String description, String category) {}

    public record GrantRole(String code, String name, String description, List<String> permissions) {}

    public record AssignableGrants(List<GrantPermission> permissions, List<GrantRole> roles) {}
}
