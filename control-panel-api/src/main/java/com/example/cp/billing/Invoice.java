package com.example.cp.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A rated billing period for a single subscription.
 *
 * <p>Lifecycle: {@code DRAFT} (created by rating the period's usage) &rarr; {@code ISSUED}
 * (finalized/locked, recorded with the {@code BillingProvider}) &rarr; {@code PAID} or {@code VOID}.
 * Only a DRAFT invoice may be issued; once issued the line items and total are frozen.
 *
 * <p>{@link #totalAmount} is the sum of the {@link InvoiceLineItem#getAmount()} of its lines, computed
 * by {@code RatingService} and persisted denormalized so reads never re-aggregate.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    public enum Status { DRAFT, ISSUED, PAID, VOID }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * Optimistic-locking version; incremented on each update so a concurrent issue/void cannot silently
     * overwrite the other (the losing writer fails with an {@code OptimisticLockException}).
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "issued_at")
    private OffsetDateTime issuedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
