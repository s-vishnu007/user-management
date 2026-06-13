package com.example.cp.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<Invoice> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /** Scoped lookup: a single invoice that must belong to the given org (cross-tenant safe). */
    Optional<Invoice> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The non-VOID invoice (if any) already covering a subscription's billing period, identified by its
     * {@code period_start}. Mirrors the partial unique index {@code uq_invoices_subscription_period}: at
     * most one such row can exist, so {@link Optional} is correct. Used by the duplicate-invoice guard so
     * a second generate for the same period returns the existing draft/issued invoice instead of
     * re-billing the period.
     */
    @Query("""
            SELECT i FROM Invoice i
            WHERE i.subscriptionId = :subscriptionId
              AND i.periodStart = :periodStart
              AND i.status <> com.example.cp.billing.Invoice$Status.VOID
            """)
    Optional<Invoice> findActiveForPeriod(@Param("subscriptionId") UUID subscriptionId,
                                          @Param("periodStart") OffsetDateTime periodStart);
}
