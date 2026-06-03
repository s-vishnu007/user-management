package com.example.cp.security;

import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Central per-target-org access checker used by all resource-scoped {@code @PreAuthorize} SpEL.
 *
 * <p>Resolves the TARGET resource's owning org and authorizes against it. The authorization order
 * for every method is fixed:
 * <ol>
 *   <li>{@code super_admin} -&gt; allow (the ONLY global bypass);</li>
 *   <li>API-key principal -&gt; allowed only when the key is bound to the target org
 *       ({@code apiKeyOrgId().equals(targetOrg)}); writes are denied for keys by default;</li>
 *   <li>human -&gt; {@code OrgMember} membership (reads) or {@code OWNER/ADMIN} rank (writes).</li>
 * </ol>
 *
 * <p>There is deliberately NO global-authority short-circuit inside this bean: it never consults
 * {@code subscription.read}, {@code license.issue}, {@code usage.read} etc. Endpoint-level authority
 * checks stay in {@code @PreAuthorize} and are AND/OR-composed there, but a cross-org bypass is
 * impossible because the checker ignores authorities for resolution. Every method is default-deny:
 * a null argument, a missing resource, or an unresolved org returns {@code false}.
 */
@Component("tenantAccess")
public class TenantAccessChecker {

    private final SubscriptionRepository subRepo;
    private final OrgMemberRepository memberRepo;
    private final LicenseTokenRepository tokenRepo;

    public TenantAccessChecker(SubscriptionRepository subRepo,
                               OrgMemberRepository memberRepo,
                               LicenseTokenRepository tokenRepo) {
        this.subRepo = subRepo;
        this.memberRepo = memberRepo;
        this.tokenRepo = tokenRepo;
    }

    // --- org-level checks -------------------------------------------------

    public boolean canAccessOrg(UUID orgId) {
        if (orgId == null) return false;
        AuthenticatedUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null) return false;
        if (u.superAdmin()) return true;
        if (u.isApiKey()) {
            // API-key membership = its bound org ONLY, no global-scope bypass.
            return orgId.equals(u.apiKeyOrgId());
        }
        return isMemberOf(u, orgId);
    }

    public boolean canManageOrg(UUID orgId) {
        if (orgId == null) return false;
        AuthenticatedUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null) return false;
        if (u.superAdmin()) return true;
        // API keys have no write scope by default -> default deny for management.
        if (u.isApiKey()) return false;
        return isManagerOf(u, orgId);
    }

    // --- subscription-scoped checks --------------------------------------

    public boolean canReadSubscription(UUID subscriptionId) {
        return resolveOrgForSubscription(subscriptionId).map(this::canAccessOrg).orElse(false);
    }

    public boolean canWriteSubscription(UUID subscriptionId) {
        return resolveOrgForSubscription(subscriptionId).map(this::canManageOrg).orElse(false);
    }

    public boolean canReadSubscriptionInOrg(UUID orgId) {
        return canAccessOrg(orgId);
    }

    public boolean canWriteSubscriptionInOrg(UUID orgId) {
        return canManageOrg(orgId);
    }

    public boolean canIssueLicenseForSubscription(UUID subscriptionId) {
        // Issuing a license is a write operation against the subscription's org.
        return canWriteSubscription(subscriptionId);
    }

    public boolean canReadUsageForSubscription(UUID subscriptionId) {
        return canReadSubscription(subscriptionId);
    }

    // --- license (jti)-scoped checks -------------------------------------

    public boolean canReadLicenseByJti(String jti) {
        return resolveOrgForJti(jti).map(this::canAccessOrg).orElse(false);
    }

    public boolean canRevokeLicenseByJti(String jti) {
        return resolveOrgForJti(jti).map(this::canManageOrg).orElse(false);
    }

    public boolean canIngestUsageForJti(String jti) {
        // For api-key ingest this enforces the key is bound to the same org that owns the license.
        return resolveOrgForJti(jti).map(this::canAccessOrg).orElse(false);
    }

    // --- internal resolution helpers -------------------------------------

    Optional<UUID> resolveOrgForSubscription(UUID subscriptionId) {
        if (subscriptionId == null) return Optional.empty();
        return subRepo.findById(subscriptionId).map(Subscription::getOrgId);
    }

    Optional<UUID> resolveOrgForJti(String jti) {
        if (jti == null || jti.isBlank()) return Optional.empty();
        return tokenRepo.findByJti(jti)
                .map(LicenseToken::getSubscriptionId)
                .flatMap(this::resolveOrgForSubscription);
    }

    private boolean isMemberOf(AuthenticatedUser u, UUID orgId) {
        if (u.userId() == null) return false;
        return memberRepo.findByOrgIdAndUserId(orgId, u.userId()).isPresent();
    }

    private boolean isManagerOf(AuthenticatedUser u, UUID orgId) {
        if (u.userId() == null) return false;
        return memberRepo.findByOrgIdAndUserId(orgId, u.userId())
                .map(m -> rank(m.getRole()) >= rank(OrgMember.Role.ADMIN))
                .orElse(false);
    }

    private int rank(OrgMember.Role r) {
        if (r == null) return 0;
        return switch (r) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case MEMBER -> 2;
            case VIEWER -> 1;
        };
    }
}
