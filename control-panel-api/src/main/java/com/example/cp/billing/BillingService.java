package com.example.cp.billing;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.common.SecurityUtils;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.example.cp.usage.UsageQuota;
import com.example.cp.usage.UsageQuotaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the provider-neutral billing pipeline: one {@link BillingAccount} per org, rating a
 * subscription period's {@code usage_quotas} into a {@link Invoice.Status#DRAFT} {@link Invoice} with
 * {@link InvoiceLineItem}s, and finalizing (issuing) a draft.
 *
 * <p>Rating math lives in {@link RatingService}; this service is the persistence + lifecycle layer and
 * the only place that touches the {@link BillingProvider}. It never edits usage or subscription state —
 * both are read through their repositories only.
 */
@Service
public class BillingService {

    private final BillingAccountRepository accountRepo;
    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineItemRepository lineItemRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final UsageQuotaRepository usageQuotaRepo;
    private final RatingService ratingService;
    private final BillingProvider billingProvider;
    private final AuditWriter auditWriter;

    public BillingService(BillingAccountRepository accountRepo,
                          InvoiceRepository invoiceRepo,
                          InvoiceLineItemRepository lineItemRepo,
                          SubscriptionRepository subscriptionRepo,
                          UsageQuotaRepository usageQuotaRepo,
                          RatingService ratingService,
                          BillingProvider billingProvider,
                          AuditWriter auditWriter) {
        this.accountRepo = accountRepo;
        this.invoiceRepo = invoiceRepo;
        this.lineItemRepo = lineItemRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.usageQuotaRepo = usageQuotaRepo;
        this.ratingService = ratingService;
        this.billingProvider = billingProvider;
        this.auditWriter = auditWriter;
    }

    // ------------------------------------------------------------------
    // Billing account
    // ------------------------------------------------------------------

    /**
     * Returns the org's billing account, creating it on first access (idempotent — one row per org,
     * enforced by the unique {@code org_id} and the in-method existence check). The existence check is
     * a check-then-insert, so two concurrent first-access calls can both miss and race the
     * {@code billing_accounts.org_id} unique constraint; the loser catches the
     * {@link DataIntegrityViolationException} and re-reads the winner's row rather than surfacing a 500.
     */
    @Transactional
    public BillingAccount getOrCreateAccount(UUID orgId, String currency) {
        return accountRepo.findByOrgId(orgId).orElseGet(() -> {
            String cur = normalizeCurrency(currency);
            String externalId = billingProvider.createCustomer(orgId, cur);
            BillingAccount account = BillingAccount.builder()
                    .id(Ids.newId())
                    .orgId(orgId)
                    .provider(billingProvider.name())
                    .externalCustomerId(externalId)
                    .currency(cur)
                    .createdAt(OffsetDateTime.now())
                    .build();
            try {
                return accountRepo.saveAndFlush(account);
            } catch (DataIntegrityViolationException race) {
                // A concurrent first-access committed the unique row between our miss and insert.
                return accountRepo.findByOrgId(orgId)
                        .orElseThrow(() -> race);
            }
        });
    }

    @Transactional(readOnly = true)
    public BillingAccount getAccount(UUID orgId) {
        return accountRepo.findByOrgId(orgId)
                .orElseThrow(() -> ApiException.notFound("No billing account for this organization"));
    }

    // ------------------------------------------------------------------
    // Invoices
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Invoice> listInvoices(UUID orgId) {
        return invoiceRepo.findByOrgIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional(readOnly = true)
    public Invoice getInvoiceForOrg(UUID orgId, UUID invoiceId) {
        return invoiceRepo.findByIdAndOrgId(invoiceId, orgId)
                .orElseThrow(() -> ApiException.notFound("Invoice not found"));
    }

    @Transactional(readOnly = true)
    public List<InvoiceLineItem> lineItems(UUID invoiceId) {
        return lineItemRepo.findByInvoiceId(invoiceId);
    }

    /**
     * Rate a single, explicit billing period of a subscription's usage into a DRAFT invoice for
     * {@code orgId}.
     *
     * <p>The subscription MUST belong to {@code orgId} (else 404, never cross-tenant). The billing account
     * is auto-created if absent so a generate call always succeeds for a valid org/subscription.
     *
     * <p><b>Period scoping (P1-10).</b> {@code period} selects exactly one usage period — the UTC
     * month boundary that {@code usage_quotas} are bucketed under (see {@code UsageIngestService}). It is
     * normalized to the start of its UTC month; {@code null} defaults to the current month. Only the
     * {@code usage_quotas} rows whose {@code period_start} equals that boundary are rated, so the invoice
     * bills exactly that period — never the merged lifetime usage. The window is the period itself
     * ({@code [start, start+1 month)}), independent of which features happened to record usage.
     *
     * <p><b>Duplicate-invoice guard (P1-10).</b> At most one non-VOID invoice may exist per
     * {@code (subscription_id, period)} — enforced both by an in-application existence check (returns the
     * existing invoice instead of re-billing) and by the partial unique index
     * {@code uq_invoices_subscription_period}. A concurrent generate that loses the index race catches the
     * {@link DataIntegrityViolationException} and returns the winner's invoice, so the operation is
     * idempotent rather than a 500 or a double-bill.
     */
    @Transactional
    public Invoice generateDraftInvoice(UUID orgId, UUID subscriptionId, OffsetDateTime period) {
        Subscription sub = subscriptionRepo.findById(subscriptionId)
                .filter(s -> orgId.equals(s.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Subscription not found for this organization"));

        OffsetDateTime periodStart = monthStartUtc(period == null ? OffsetDateTime.now() : period);
        OffsetDateTime periodEnd = periodStart.plusMonths(1);

        // Duplicate-invoice guard: a non-VOID invoice already covering this period is returned as-is
        // rather than re-billing the period. (The DB partial unique index is the concurrency backstop.)
        Optional<Invoice> existing = invoiceRepo.findActiveForPeriod(subscriptionId, periodStart);
        if (existing.isPresent()) {
            return existing.get();
        }

        BillingAccount account = getOrCreateAccount(orgId, null);

        // Only this period's quota rows are rated (no lifetime merge). usage_quotas are keyed by
        // (subscription_id, feature_key, period_start), so equality on period_start selects exactly the
        // requested period; the repository (in the usage package) is read by subscription and filtered here.
        Map<String, BigDecimal> consumedByFeature = new LinkedHashMap<>();
        for (UsageQuota q : usageQuotaRepo.findBySubscriptionId(subscriptionId)) {
            if (!periodStart.isEqual(q.getPeriodStart())) {
                continue;
            }
            BigDecimal consumed = q.getConsumedValue() == null ? BigDecimal.ZERO : q.getConsumedValue();
            consumedByFeature.merge(q.getFeatureKey(), consumed, BigDecimal::add);
        }

        RatingService.RatedInvoice rated =
                ratingService.rate(sub.getPlanId(), consumedByFeature, account.getCurrency());

        Invoice invoice = Invoice.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .subscriptionId(subscriptionId)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .status(Invoice.Status.DRAFT)
                .currency(account.getCurrency())
                .totalAmount(rated.total())
                .createdAt(OffsetDateTime.now())
                .build();

        Invoice saved;
        try {
            saved = invoiceRepo.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException race) {
            // A concurrent generate for the same (subscription, period) committed first and tripped the
            // partial unique index. Return its invoice idempotently instead of double-billing / 500.
            return invoiceRepo.findActiveForPeriod(subscriptionId, periodStart)
                    .orElseThrow(() -> race);
        }

        for (RatingService.RatedLine line : rated.lines()) {
            lineItemRepo.save(InvoiceLineItem.builder()
                    .id(Ids.newId())
                    .invoiceId(saved.getId())
                    .featureKey(line.featureKey())
                    .quantity(line.quantity())
                    .unitAmount(line.unitAmount())
                    .amount(line.amount())
                    .description(line.description())
                    .build());
        }
        return saved;
    }

    /** Normalizes an instant to the start of its UTC month — the period key usage_quotas are bucketed by. */
    private static OffsetDateTime monthStartUtc(OffsetDateTime t) {
        OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    }

    /**
     * Finalize a DRAFT invoice → ISSUED. Records the charge + finalization with the {@link BillingProvider}
     * and stamps {@code issued_at}. Issuing is audited fail-closed (the audit row commits atomically with
     * the status change). A non-DRAFT invoice is a 409.
     *
     * <p><b>Concurrency (P2).</b> The status is flipped to ISSUED and the row flushed (taking the
     * {@code @Version} bump) <em>before</em> the {@link BillingProvider} call. A concurrent issue or
     * concurrent VOID therefore fails its own optimistic-lock check at flush time
     * ({@code ObjectOptimisticLockingFailureException} → mapped to 409 centrally) instead of a VOID being
     * overwritten back to ISSUED or the charge being recorded twice. The provider call follows the durable
     * status transition and is required to be idempotent per {@link BillingProvider}.
     */
    @Transactional
    public Invoice issueInvoice(UUID orgId, UUID invoiceId) {
        Invoice invoice = getInvoiceForOrg(orgId, invoiceId);
        if (invoice.getStatus() != Invoice.Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT invoice can be issued (current status: "
                    + invoice.getStatus() + ")");
        }
        BillingAccount account = getOrCreateAccount(orgId, invoice.getCurrency());

        // Claim the transition first: flush the DRAFT -> ISSUED change so the @Version guard rejects any
        // concurrent issue/void BEFORE we ask the provider to move money. Idempotent provider calls follow.
        invoice.setStatus(Invoice.Status.ISSUED);
        invoice.setIssuedAt(OffsetDateTime.now());
        Invoice saved = invoiceRepo.saveAndFlush(invoice);

        billingProvider.recordCharge(account.getExternalCustomerId(), saved.getId(),
                saved.getTotalAmount(), saved.getCurrency());
        billingProvider.finalizeInvoice(account.getExternalCustomerId(), saved.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("org_id", orgId.toString());
        payload.put("subscription_id", invoice.getSubscriptionId().toString());
        payload.put("total_amount", invoice.getTotalAmount().toPlainString());
        payload.put("currency", invoice.getCurrency());
        payload.put("provider", billingProvider.name());
        auditWriter.record(actorUserId(), orgId, "billing.invoice.issued", "invoice",
                saved.getId().toString(), payload, AuditContext.currentIp(), AuditOutcome.SUCCESS, true);
        AuditContext.markRecorded();
        return saved;
    }

    /**
     * Void a DRAFT or ISSUED invoice → VOID. Audited fail-closed. A PAID or already-VOID invoice is a 409.
     *
     * <p><b>Concurrency (P2).</b> The VOID transition is flushed (taking the {@code @Version} bump) so a
     * concurrent issue that read the same DRAFT loses the optimistic-lock race rather than overwriting this
     * VOID back to ISSUED. Once VOIDed the {@code (subscription, period)} slot is free again, so a fresh
     * draft can be generated for the period (the unique index is partial on {@code status <> 'VOID'}).
     */
    @Transactional
    public Invoice voidInvoice(UUID orgId, UUID invoiceId) {
        Invoice invoice = getInvoiceForOrg(orgId, invoiceId);
        if (invoice.getStatus() == Invoice.Status.PAID || invoice.getStatus() == Invoice.Status.VOID) {
            throw ApiException.conflict("Cannot void an invoice in status " + invoice.getStatus());
        }
        invoice.setStatus(Invoice.Status.VOID);
        Invoice saved = invoiceRepo.saveAndFlush(invoice);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("org_id", orgId.toString());
        payload.put("subscription_id", invoice.getSubscriptionId().toString());
        payload.put("total_amount", invoice.getTotalAmount().toPlainString());
        auditWriter.record(actorUserId(), orgId, "billing.invoice.voided", "invoice",
                saved.getId().toString(), payload, AuditContext.currentIp(), AuditOutcome.SUCCESS, true);
        AuditContext.markRecorded();
        return saved;
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "USD";
        }
        return currency.trim().toUpperCase();
    }

    private static UUID actorUserId() {
        UUID actor = AuditContext.currentActorUserId();
        if (actor != null) return actor;
        return SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
    }
}
