package com.example.cp.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One rated line of an {@link Invoice}: a feature's metered {@link #quantity} times its
 * {@link #unitAmount}, yielding {@link #amount}. Stored as a first-class entity (not an
 * {@code @ElementCollection}) so each line has a stable id and can be queried independently.
 */
@Entity
@Table(name = "invoice_line_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceLineItem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "feature_key", nullable = false, length = 64)
    private String featureKey;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_amount", nullable = false)
    private BigDecimal unitAmount;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "description")
    private String description;
}
