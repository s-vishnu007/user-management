package com.example.cp.subscriptions;

import com.example.cp.security.TenantAccessChecker;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Legacy security helper exposed as bean "subAccess" for use in @PreAuthorize SpEL.
 *
 * <p>Superseded by {@link TenantAccessChecker} ("tenantAccess"), which is now the single source of
 * truth for tenant-scoped access. The global-authority short-circuits that previously lived here
 * ({@code subscription.read} / {@code license.issue}) have been REMOVED — every method now delegates
 * to {@code tenantAccess}, so {@code super_admin} is the only global bypass and api-key principals
 * are constrained to their bound org. Retained only for backward compatibility with any external
 * SpEL still referencing {@code @subAccess}; all in-tree references have been migrated to
 * {@code @tenantAccess}.
 */
@Component("subAccess")
public class SubscriptionAccess {

    private final TenantAccessChecker tenantAccess;

    public SubscriptionAccess(TenantAccessChecker tenantAccess) {
        this.tenantAccess = tenantAccess;
    }

    public boolean isOrgMember(UUID orgId) {
        return tenantAccess.canAccessOrg(orgId);
    }

    public boolean canReadSubscription(UUID subscriptionId) {
        return tenantAccess.canReadSubscription(subscriptionId);
    }

    public boolean canDownloadLicense(UUID subscriptionId) {
        return tenantAccess.canReadSubscription(subscriptionId);
    }
}
