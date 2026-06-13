package com.example.cp.sso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SsoProviderRepository extends JpaRepository<SsoProvider, UUID> {

    List<SsoProvider> findByOrgId(UUID orgId);

    List<SsoProvider> findByEnabledTrue();

    /**
     * Tenant-scoped single-row lookup: an id that belongs to another org (or is absent) returns
     * empty so callers can 404 instead of leaking/mutating a cross-tenant provider (mirrors
     * {@code WebhookSubscriptionRepository.findByIdAndOrgId}).
     */
    Optional<SsoProvider> findByIdAndOrgId(UUID id, UUID orgId);
}
