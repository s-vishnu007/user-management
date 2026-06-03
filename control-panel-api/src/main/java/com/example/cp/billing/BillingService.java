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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * enforced by the unique {@code org_id} and the in-method existence check).
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
            return accountRepo.save(account);
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
     * Rate a subscription's current/closed usage period into a new DRAFT invoice for {@code orgId}.
     *
     * <p>The subscription MUST belong to {@code orgId} (else 404, never cross-tenant). The billing account
     * is auto-created if absent so a generate call always succeeds for a valid org/subscription. The
     * rated period is taken from the subscription's {@code usage_quotas} rows: the period window is the
     * min {@code period_start} .. max {@code period_end} across those rows, defaulting to the
     * subscription window when there is no metered usage yet (an empty, zero-total draft).
     */
    @Transactional
    public Invoice generateDraftInvoice(UUID orgId, UUID subscriptionId) {
        Subscription sub = subscriptionRepo.findById(subscriptionId)
                .filter(s -> orgId.equals(s.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Subscription not found for this organization"));

        BillingAccount account = getOrCreateAccount(orgId, null);

        List<UsageQuota> quotas = usageQuotaRepo.findBySubscriptionId(subscriptionId);

        Map<String, BigDecimal> consumedByFeature = new LinkedHashMap<>();
        for (UsageQuota q : quotas) {
            BigDecimal consumed = q.getConsumedValue() == null ? BigDecimal.ZERO : q.getConsumedValue();
            consumedByFeature.merge(q.getFeatureKey(), consumed, BigDecimal::add);
        }

        OffsetDateTime periodStart = quotas.stream()
                .map(UsageQuota::getPeriodStart)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(sub.getStartsAt());
        OffsetDateTime periodEnd = quotas.stream()
                .map(UsageQuota::getPeriodEnd)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(sub.getEndsAt());

        RatingService.RatedInvoice rated = ratingService.rate(sub.getPlanId(), consumedByFeature);

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
        Invoice saved = invoiceRepo.save(invoice);

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

    /**
     * Finalize a DRAFT invoice → ISSUED. Records the charge + finalization with the {@link BillingProvider}
     * and stamps {@code issued_at}. Issuing is audited fail-closed (the audit row commits atomically with
     * the status change). A non-DRAFT invoice is a 409.
     */
    @Transactional
    public Invoice issueInvoice(UUID orgId, UUID invoiceId) {
        Invoice invoice = getInvoiceForOrg(orgId, invoiceId);
        if (invoice.getStatus() != Invoice.Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT invoice can be issued (current status: "
                    + invoice.getStatus() + ")");
        }
        BillingAccount account = getOrCreateAccount(orgId, invoice.getCurrency());

        billingProvider.recordCharge(account.getExternalCustomerId(), invoice.getId(),
                invoice.getTotalAmount(), invoice.getCurrency());
        billingProvider.finalizeInvoice(account.getExternalCustomerId(), invoice.getId());

        invoice.setStatus(Invoice.Status.ISSUED);
        invoice.setIssuedAt(OffsetDateTime.now());
        Invoice saved = invoiceRepo.save(invoice);

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
     */
    @Transactional
    public Invoice voidInvoice(UUID orgId, UUID invoiceId) {
        Invoice invoice = getInvoiceForOrg(orgId, invoiceId);
        if (invoice.getStatus() == Invoice.Status.PAID || invoice.getStatus() == Invoice.Status.VOID) {
            throw ApiException.conflict("Cannot void an invoice in status " + invoice.getStatus());
        }
        invoice.setStatus(Invoice.Status.VOID);
        Invoice saved = invoiceRepo.save(invoice);

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
