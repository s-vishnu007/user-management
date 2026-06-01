package com.example.cp.subscriptions;

import com.example.cp.common.SecurityUtils;
import com.example.cp.orgs.OrgMemberRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Security helper exposed as bean "subAccess" for use in @PreAuthorize SpEL.
 * Mirrors Agent 5's "@orgAccess.isMember(#orgId)" pattern but scoped to subscription ids.
 */
@Component("subAccess")
public class SubscriptionAccess {

    private final SubscriptionRepository subRepo;
    private final OrgMemberRepository memberRepo;

    public SubscriptionAccess(SubscriptionRepository subRepo, OrgMemberRepository memberRepo) {
        this.subRepo = subRepo;
        this.memberRepo = memberRepo;
    }

    public boolean isOrgMember(UUID orgId) {
        return SecurityUtils.currentUser()
                .map(u -> u.superAdmin() || memberRepo.findByOrgIdAndUserId(orgId, u.userId()).isPresent())
                .orElse(false);
    }

    public boolean canReadSubscription(UUID subscriptionId) {
        return SecurityUtils.currentUser().map(u -> {
            if (u.superAdmin() || u.hasAuthority("subscription.read")) return true;
            return subRepo.findById(subscriptionId)
                    .map(s -> memberRepo.findByOrgIdAndUserId(s.getOrgId(), u.userId()).isPresent())
                    .orElse(false);
        }).orElse(false);
    }

    public boolean canDownloadLicense(UUID subscriptionId) {
        return SecurityUtils.currentUser().map(u -> {
            if (u.superAdmin()
                    || u.hasAuthority("license.issue")
                    || u.hasAuthority("subscription.read")) return true;
            return subRepo.findById(subscriptionId)
                    .map(s -> memberRepo.findByOrgIdAndUserId(s.getOrgId(), u.userId()).isPresent())
                    .orElse(false);
        }).orElse(false);
    }
}
