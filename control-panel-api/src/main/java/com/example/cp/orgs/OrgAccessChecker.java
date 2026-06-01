package com.example.cp.orgs;

import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * SpEL-accessible helper. Use as `@orgAccess.isMember(#orgId)` in `@PreAuthorize`.
 */
@Component("orgAccess")
public class OrgAccessChecker {

    private final OrgService orgService;

    public OrgAccessChecker(OrgService orgService) {
        this.orgService = orgService;
    }

    public boolean isMember(UUID orgId) {
        Optional<AuthenticatedUser> me = SecurityUtils.currentUser();
        if (me.isEmpty()) return false;
        if (me.get().superAdmin()) return true;
        return orgService.isMember(orgId, me.get().userId());
    }

    public boolean hasRole(UUID orgId, String roleName) {
        Optional<AuthenticatedUser> me = SecurityUtils.currentUser();
        if (me.isEmpty()) return false;
        if (me.get().superAdmin()) return true;
        Optional<OrgMember.Role> r = orgService.roleOf(orgId, me.get().userId());
        if (r.isEmpty()) return false;
        try {
            OrgMember.Role required = OrgMember.Role.valueOf(roleName);
            return rank(r.get()) >= rank(required);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public boolean isOwnerOrAdmin(UUID orgId) {
        return hasRole(orgId, "ADMIN");
    }

    private int rank(OrgMember.Role r) {
        return switch (r) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case MEMBER -> 2;
            case VIEWER -> 1;
        };
    }
}
