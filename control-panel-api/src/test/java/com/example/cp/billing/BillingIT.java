package com.example.cp.billing;

import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.plans.PlanService;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.usage.UsageQuota;
import com.example.cp.usage.UsageQuotaRepository;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP + tenancy integration tests for the billing pipeline on {@link AbstractIntegrationTest}.
 *
 * <p>Proves the contract end-to-end:
 * <ul>
 *   <li>{@code POST invoices/generate} rates the subscription period's {@code usage_quotas}
 *       {@code consumed_value} into a DRAFT invoice with per-feature line items and a correct total,
 *       using the plan's {@code price.*} price book;</li>
 *   <li>{@code POST invoices/{id}/issue} moves DRAFT → ISSUED and stamps {@code issuedAt};</li>
 *   <li>reads are gated by {@code billing.read} + {@code @tenantAccess.canAccessOrg}; writes by
 *       {@code @tenantAccess.canManageOrg} — an admin of org B cannot touch org A's billing, and a
 *       read-only member cannot generate.</li>
 * </ul>
 */
class BillingIT extends AbstractIntegrationTest {

    @Autowired private PlanService planService;
    @Autowired private UsageQuotaRepository usageQuotaRepo;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceLineItemRepository lineItemRepository;
    @Autowired private BillingAccountRepository billingAccountRepository;

    /** Creates an isolated plan whose price book is the given {@code price.*} features. */
    private Plan planWithPrices(Map<String, Object> priceFeatures) {
        Plan plan = seedNewPlan("billing-" + rnd(), 365);
        planService.replaceFeatures(plan.getId(), priceFeatures);
        return plan;
    }

    private void seedQuota(UUID subscriptionId, String featureKey, OffsetDateTime start,
                           OffsetDateTime end, BigDecimal consumed) {
        usageQuotaRepo.save(UsageQuota.builder()
                .subscriptionId(subscriptionId)
                .featureKey(featureKey)
                .periodStart(start)
                .periodEnd(end)
                .limitValue(null)
                .consumedValue(consumed)
                .build());
    }

    /** Start of the current UTC month — the period boundary {@code usage_quotas} are bucketed under and
     *  the default period {@code generate} bills. */
    private static OffsetDateTime currentMonthStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    }

    /** Seeds a quota row in the given UTC month bucket ({@code monthStart .. monthStart + 1 month}). */
    private void seedMonthQuota(UUID subscriptionId, String featureKey, OffsetDateTime monthStart,
                                BigDecimal consumed) {
        seedQuota(subscriptionId, featureKey, monthStart, monthStart.plusMonths(1), consumed);
    }

    @Test
    void generate_thenIssue_producesIssuedInvoiceWithLineItemsAndTotal() throws Exception {
        Organization org = seedOrg("Billing Org");
        User admin = seedUser("bill-admin-" + rnd() + "@example.com", "Bill Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of(
                "price.seats", 5,
                "price.api_calls", "0.01"
        ));
        Subscription sub = seedSubscription(org.getId(), plan.getId());

        OffsetDateTime month = currentMonthStart();
        seedMonthQuota(sub.getId(), "seats", month, new BigDecimal("3"));        // 3 * 5    = 15.00
        seedMonthQuota(sub.getId(), "api_calls", month, new BigDecimal("1000")); // 1000*.01 = 10.00

        // Generate a DRAFT invoice for the subscription's current period.
        String generateBody = objectMapper.writeValueAsString(Map.of("subscriptionId", sub.getId().toString()));
        String resp = mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(generateBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.lineItems.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode invNode = objectMapper.readTree(resp);
        UUID invoiceId = UUID.fromString(invNode.get("id").asText());

        // Line items persisted and sum to the total.
        var lines = lineItemRepository.findByInvoiceId(invoiceId);
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("25.00");

        // A billing account was auto-created for the org by the manual provider.
        assertThat(billingAccountRepository.findByOrgId(org.getId())).isPresent();

        // Issue the DRAFT -> ISSUED, issuedAt stamped.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/{id}/issue", org.getId(), invoiceId)
                        .with(asUser(admin, "billing.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.issuedAt").exists());

        // Re-issuing an already-ISSUED invoice is a conflict.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/{id}/issue", org.getId(), invoiceId)
                        .with(asUser(admin, "billing.read")))
                .andExpect(status().isConflict());

        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus())
                .isEqualTo(Invoice.Status.ISSUED);
    }

    @Test
    void generate_withNoUsage_producesEmptyZeroTotalDraft() throws Exception {
        Organization org = seedOrg("Empty Billing Org");
        User admin = seedUser("bill-empty-" + rnd() + "@example.com", "Empty Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of("price.seats", 5));
        Subscription sub = seedSubscription(org.getId(), plan.getId());

        String body = objectMapper.writeValueAsString(Map.of("subscriptionId", sub.getId().toString()));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalAmount").value(0.00))
                .andExpect(jsonPath("$.lineItems.length()").value(0));
    }

    @Test
    void reads_requireBillingReadAuthority() throws Exception {
        Organization org = seedOrg("Read Auth Org");
        User member = seedUser("bill-noauth-" + rnd() + "@example.com", "No Auth", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.MEMBER);

        // Member of the org but WITHOUT the billing.read authority -> 403 on reads.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/billing/invoices", org.getId())
                        .with(asUser(member)))
                .andExpect(status().isForbidden());

        // With billing.read, the list is reachable (empty).
        mockMvc.perform(get("/api/v1/orgs/{orgId}/billing/invoices", org.getId())
                        .with(asUser(member, "billing.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void billing_isTenantScoped_adminOfOrgB_cannotTouchOrgA() throws Exception {
        Organization orgA = seedOrg("Billing Org A");
        Organization orgB = seedOrg("Billing Org B");

        User adminA = seedUser("bill-a-" + rnd() + "@example.com", "Admin A", false);
        addOrgMember(orgA.getId(), adminA.getId(), OrgMember.Role.ADMIN);
        User adminB = seedUser("bill-b-" + rnd() + "@example.com", "Admin B", false);
        addOrgMember(orgB.getId(), adminB.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of("price.seats", "5"));
        Subscription subA = seedSubscription(orgA.getId(), plan.getId());
        seedMonthQuota(subA.getId(), "seats", currentMonthStart(), new BigDecimal("2"));

        // adminA generates an invoice in org A.
        String body = objectMapper.writeValueAsString(Map.of("subscriptionId", subA.getId().toString()));
        String resp = mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", orgA.getId())
                        .with(asUser(adminA, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID invoiceAId = UUID.fromString(objectMapper.readTree(resp).get("id").asText());

        // adminB cannot read org A's billing.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/billing/invoices", orgA.getId())
                        .with(asUser(adminB, "billing.read")))
                .andExpect(status().isForbidden());

        // adminB cannot generate in org A.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", orgA.getId())
                        .with(asUser(adminB, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());

        // An invoice id from org A addressed under org B's path (by org B's admin) is a 404 — never a
        // cross-tenant issue.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/{id}/issue", orgB.getId(), invoiceAId)
                        .with(asUser(adminB, "billing.read")))
                .andExpect(status().isNotFound());

        // org A's invoice is untouched (still DRAFT).
        assertThat(invoiceRepository.findById(invoiceAId).orElseThrow().getStatus())
                .isEqualTo(Invoice.Status.DRAFT);
    }

    @Test
    void generate_forSubscriptionNotInOrg_is404() throws Exception {
        Organization orgA = seedOrg("Mismatch Org A");
        Organization orgB = seedOrg("Mismatch Org B");
        User adminB = seedUser("bill-mismatch-" + rnd() + "@example.com", "Admin B", false);
        addOrgMember(orgB.getId(), adminB.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of("price.seats", "5"));
        Subscription subA = seedSubscription(orgA.getId(), plan.getId());

        // adminB tries to rate org A's subscription via his own org B path: sub not in org B -> 404.
        String body = objectMapper.writeValueAsString(Map.of("subscriptionId", subA.getId().toString()));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", orgB.getId())
                        .with(asUser(adminB, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void superAdmin_canManageAnyOrgBilling() throws Exception {
        Organization org = seedOrg("SA Billing Org");
        User sa = seedUser("bill-sa-" + rnd() + "@example.com", "Super", true);

        Plan plan = planWithPrices(Map.of("price.seats", "5"));
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        seedMonthQuota(sub.getId(), "seats", currentMonthStart(), new BigDecimal("4"));

        String body = objectMapper.writeValueAsString(Map.of("subscriptionId", sub.getId().toString()));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asSuperAdmin(sa))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(20.00)); // 4 * 5
    }

    @Test
    void generate_billsOnlyTheRequestedPeriod_notLifetimeUsage() throws Exception {
        Organization org = seedOrg("Period Billing Org");
        User admin = seedUser("bill-period-" + rnd() + "@example.com", "Period Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of("price.seats", 5));
        Subscription sub = seedSubscription(org.getId(), plan.getId());

        OffsetDateTime thisMonth = currentMonthStart();
        OffsetDateTime lastMonth = thisMonth.minusMonths(1);
        OffsetDateTime twoMonthsAgo = thisMonth.minusMonths(2);
        // Usage spread across three distinct monthly periods.
        seedMonthQuota(sub.getId(), "seats", twoMonthsAgo, new BigDecimal("100")); // 500.00 (not billed)
        seedMonthQuota(sub.getId(), "seats", lastMonth, new BigDecimal("10"));      //  50.00 (explicit)
        seedMonthQuota(sub.getId(), "seats", thisMonth, new BigDecimal("3"));       //  15.00 (default)

        // Default generate (no period) bills ONLY the current month — never the merged lifetime usage.
        String defaultBody = objectMapper.writeValueAsString(Map.of("subscriptionId", sub.getId().toString()));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(defaultBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(15.00))         // 3 * 5, current month only
                .andExpect(jsonPath("$.lineItems.length()").value(1))
                .andExpect(jsonPath("$.periodStart").exists());

        // An explicit prior period bills only that period's usage.
        Map<String, Object> lastMonthReq = new HashMap<>();
        lastMonthReq.put("subscriptionId", sub.getId().toString());
        lastMonthReq.put("period", lastMonth.toString());
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(lastMonthReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(50.00)); // 10 * 5, last month only

        // Two distinct invoices exist (one per period), neither billing lifetime usage.
        assertThat(invoiceRepository.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId())).hasSize(2);
    }

    @Test
    void generate_isIdempotentPerPeriod_noDoubleBilling() throws Exception {
        Organization org = seedOrg("Dupe Guard Org");
        User admin = seedUser("bill-dupe-" + rnd() + "@example.com", "Dupe Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of("price.seats", 5));
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        seedMonthQuota(sub.getId(), "seats", currentMonthStart(), new BigDecimal("3")); // 15.00

        String body = objectMapper.writeValueAsString(Map.of("subscriptionId", sub.getId().toString()));

        String first = mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(15.00))
                .andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(objectMapper.readTree(first).get("id").asText());

        // A second generate for the same subscription+period returns the SAME invoice (idempotent),
        // not a fresh one re-billing the period.
        String second = mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(15.00))
                .andReturn().getResponse().getContentAsString();
        UUID secondId = UUID.fromString(objectMapper.readTree(second).get("id").asText());

        assertThat(secondId).isEqualTo(firstId);
        // Exactly one invoice for the subscription — the period was billed once.
        assertThat(invoiceRepository.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId())).hasSize(1);
    }

    @Test
    void voidedInvoice_allowsRegeneratingThePeriod() throws Exception {
        Organization org = seedOrg("Void Regen Org");
        User admin = seedUser("bill-void-" + rnd() + "@example.com", "Void Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        Plan plan = planWithPrices(Map.of("price.seats", 5));
        Subscription sub = seedSubscription(org.getId(), plan.getId());
        seedMonthQuota(sub.getId(), "seats", currentMonthStart(), new BigDecimal("2")); // 10.00

        String body = objectMapper.writeValueAsString(Map.of("subscriptionId", sub.getId().toString()));
        String first = mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(objectMapper.readTree(first).get("id").asText());

        // Void the draft; the (subscription, period) slot frees up (partial unique index on status<>'VOID').
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/{id}/void", org.getId(), firstId)
                        .with(asUser(admin, "billing.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));

        // Re-generating the same period now produces a NEW draft (the voided one no longer occupies it).
        String regen = mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/invoices/generate", org.getId())
                        .with(asUser(admin, "billing.read"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        UUID regenId = UUID.fromString(objectMapper.readTree(regen).get("id").asText());

        assertThat(regenId).isNotEqualTo(firstId);
    }
}
