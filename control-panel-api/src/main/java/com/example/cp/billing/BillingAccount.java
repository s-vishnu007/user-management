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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One billing account per organization. Binds an org to a provider-neutral
 * {@code (provider, external_customer_id, currency)} triple.
 *
 * <p>The {@link #provider} is the {@code BillingProvider.name()} that created the account (e.g.
 * {@code "manual"}); {@link #externalCustomerId} is the opaque id that provider returns for the org's
 * customer record. With {@code ManualBillingProvider} there is no external system, so the customer id
 * is generated locally and no external call is ever made — the account exists purely so that invoices
 * can later be attached to a stable per-org customer.
 */
@Entity
@Table(name = "billing_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingAccount {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, unique = true)
    private UUID orgId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "external_customer_id")
    private String externalCustomerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
