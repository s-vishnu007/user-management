package com.example.cp.billing;

import java.util.UUID;

/**
 * Provider-neutral abstraction over a billing/payment backend. The control panel tracks entitlements
 * and rates usage into invoices internally; this interface is the seam where a real payment provider
 * (Stripe, Paddle, etc.) would plug in. No implementation in this codebase contacts an external system
 * — the default {@link ManualBillingProvider} records everything to the local database — but the API is
 * deliberately shaped like a typical provider SDK so a real one can be dropped in without touching the
 * rating, invoicing, or controller layers.
 *
 * <p>Implementations MUST be side-effect-idempotent enough to be safe under retries: {@code createCustomer}
 * for an org that already has an external id should return the existing id rather than create a duplicate
 * (the caller, {@link BillingService}, enforces one {@link BillingAccount} per org).
 */
public interface BillingProvider {

    /**
     * Stable provider name persisted on {@link BillingAccount#getProvider()} and {@code invoices}-related
     * audit. Lowercase, no spaces (e.g. {@code "manual"}, {@code "stripe"}).
     */
    String name();

    /**
     * Ensure a customer record exists for the org and return its provider-side id. For
     * {@link ManualBillingProvider} this is a locally generated opaque id (no network call).
     *
     * @param orgId    the organization the customer represents
     * @param currency ISO-4217 currency code the account bills in (e.g. {@code "USD"})
     * @return the external customer id to store on the {@link BillingAccount}
     */
    String createCustomer(UUID orgId, String currency);

    /**
     * Record a charge against the account for the given invoice total. Returns an opaque provider charge
     * reference. The manual provider performs no capture — it is a bookkeeping no-op that returns a local
     * reference — but a real provider would initiate payment here.
     *
     * @param externalCustomerId the provider customer id from {@link #createCustomer}
     * @param invoiceId          the local invoice being charged
     * @param amount             the total amount (minor or major units per provider convention; the
     *                           manual provider treats it as the major-unit decimal stored on the invoice)
     * @param currency           ISO-4217 currency code
     * @return an opaque charge reference
     */
    String recordCharge(String externalCustomerId, UUID invoiceId, java.math.BigDecimal amount, String currency);

    /**
     * Finalize an invoice with the provider (lock it / mark it issued on the provider side). Returns an
     * opaque provider invoice reference. The manual provider returns a local reference and performs no
     * external call.
     *
     * @param externalCustomerId the provider customer id
     * @param invoiceId          the local invoice being finalized
     * @return an opaque provider invoice reference
     */
    String finalizeInvoice(String externalCustomerId, UUID invoiceId);
}
