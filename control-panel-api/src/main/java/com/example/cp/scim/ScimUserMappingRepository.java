package com.example.cp.scim;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ScimUserMapping}. Every finder is org-scoped: a SCIM client may only ever see
 * or address mappings within its own org, so there is deliberately no bare {@code findById} used by
 * the service for tenant-facing reads (cross-tenant isolation is enforced by always pairing the id /
 * externalId with the caller's orgId).
 */
@Repository
public interface ScimUserMappingRepository extends JpaRepository<ScimUserMapping, UUID> {

    Optional<ScimUserMapping> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<ScimUserMapping> findByOrgIdAndExternalId(UUID orgId, String externalId);

    Optional<ScimUserMapping> findByOrgIdAndUserId(UUID orgId, UUID userId);

    boolean existsByOrgIdAndUserId(UUID orgId, UUID userId);

    Page<ScimUserMapping> findByOrgId(UUID orgId, Pageable pageable);

    List<ScimUserMapping> findByOrgId(UUID orgId);

    long countByOrgId(UUID orgId);
}
