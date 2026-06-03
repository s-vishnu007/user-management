package com.example.cp.billing;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Provider-agnostic billing API for an organization. All endpoints are org-scoped and authorized through
 * {@link com.example.cp.security.TenantAccessChecker}:
 * <ul>
 *   <li><b>Reads</b> (account, invoices, invoice detail) require {@code billing.read} AND
 *       {@code @tenantAccess.canAccessOrg(orgId)} — any member of the org (or super-admin / an api-key
 *       bound to the org) with the read scope.</li>
 *   <li><b>Writes</b> (generate / issue / void) require {@code @tenantAccess.canManageOrg(orgId)} —
 *       OWNER/ADMIN of the org or super-admin; api-key principals are denied writes by the checker.</li>
 * </ul>
 * The combined SpEL keeps cross-tenant access impossible (the checker ignores global authorities), while
 * the authority gate keeps a read-only role from mutating billing state.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/account")
    @PreAuthorize("hasAuthority('billing.read') and @tenantAccess.canAccessOrg(#orgId)")
    public BillingAccountDto getAccount(@PathVariable UUID orgId) {
        return BillingAccountDto.from(billingService.getAccount(orgId));
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing.read') and @tenantAccess.canAccessOrg(#orgId)")
    public List<InvoiceDto> listInvoices(@PathVariable UUID orgId) {
        return billingService.listInvoices(orgId).stream()
                .map(inv -> InvoiceDto.from(inv, billingService.lineItems(inv.getId())))
                .toList();
    }

    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize("hasAuthority('billing.read') and @tenantAccess.canAccessOrg(#orgId)")
    public InvoiceDto getInvoice(@PathVariable UUID orgId, @PathVariable UUID invoiceId) {
        Invoice inv = billingService.getInvoiceForOrg(orgId, invoiceId);
        return InvoiceDto.from(inv, billingService.lineItems(inv.getId()));
    }

    @PostMapping("/invoices/generate")
    @PreAuthorize("@tenantAccess.canManageOrg(#orgId)")
    public ResponseEntity<InvoiceDto> generate(@PathVariable UUID orgId,
                                               @RequestBody GenerateRequest body) {
        Invoice inv = billingService.generateDraftInvoice(orgId, body.subscriptionId());
        return ResponseEntity.status(201)
                .body(InvoiceDto.from(inv, billingService.lineItems(inv.getId())));
    }

    @PostMapping("/invoices/{invoiceId}/issue")
    @PreAuthorize("@tenantAccess.canManageOrg(#orgId)")
    public InvoiceDto issue(@PathVariable UUID orgId, @PathVariable UUID invoiceId) {
        Invoice inv = billingService.issueInvoice(orgId, invoiceId);
        return InvoiceDto.from(inv, billingService.lineItems(inv.getId()));
    }

    @PostMapping("/invoices/{invoiceId}/void")
    @PreAuthorize("@tenantAccess.canManageOrg(#orgId)")
    public InvoiceDto voidInvoice(@PathVariable UUID orgId, @PathVariable UUID invoiceId) {
        Invoice inv = billingService.voidInvoice(orgId, invoiceId);
        return InvoiceDto.from(inv, billingService.lineItems(inv.getId()));
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record GenerateRequest(@NotNull UUID subscriptionId) {}

    public record BillingAccountDto(UUID id, UUID orgId, String provider, String externalCustomerId,
                                    String currency, OffsetDateTime createdAt) {
        static BillingAccountDto from(BillingAccount a) {
            return new BillingAccountDto(a.getId(), a.getOrgId(), a.getProvider(),
                    a.getExternalCustomerId(), a.getCurrency(), a.getCreatedAt());
        }
    }

    public record LineItemDto(UUID id, String featureKey, BigDecimal quantity, BigDecimal unitAmount,
                              BigDecimal amount, String description) {
        static LineItemDto from(InvoiceLineItem li) {
            return new LineItemDto(li.getId(), li.getFeatureKey(), li.getQuantity(), li.getUnitAmount(),
                    li.getAmount(), li.getDescription());
        }
    }

    public record InvoiceDto(UUID id, UUID orgId, UUID subscriptionId, OffsetDateTime periodStart,
                             OffsetDateTime periodEnd, String status, String currency,
                             BigDecimal totalAmount, OffsetDateTime issuedAt, OffsetDateTime createdAt,
                             List<LineItemDto> lineItems) {
        static InvoiceDto from(Invoice inv, List<InvoiceLineItem> lines) {
            return new InvoiceDto(inv.getId(), inv.getOrgId(), inv.getSubscriptionId(),
                    inv.getPeriodStart(), inv.getPeriodEnd(), inv.getStatus().name(), inv.getCurrency(),
                    inv.getTotalAmount(), inv.getIssuedAt(), inv.getCreatedAt(),
                    lines.stream().map(LineItemDto::from).toList());
        }
    }
}
