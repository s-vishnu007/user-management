package com.example.cp.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<Invoice> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /** Scoped lookup: a single invoice that must belong to the given org (cross-tenant safe). */
    Optional<Invoice> findByIdAndOrgId(UUID id, UUID orgId);
}
