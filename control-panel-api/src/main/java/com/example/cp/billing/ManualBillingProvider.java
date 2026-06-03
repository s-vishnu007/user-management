package com.example.cp.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Default, dependency-free {@link BillingProvider}. It makes NO external/network calls: customer and
 * charge/invoice references are generated locally and the operations are pure bookkeeping. This lets the
 * full billing pipeline (account -&gt; rate -&gt; invoice -&gt; issue) run end-to-end in dev/test and in
 * deployments that track money manually, while leaving a clean seam for a real provider implementation
 * to be registered later (it can be marked {@code @Primary} to take precedence over this default).
 */
@Component
public class ManualBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(ManualBillingProvider.class);

    public static final String NAME = "manual";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String createCustomer(UUID orgId, String currency) {
        // No external system: derive a stable, opaque local customer id.
        String ref = "manual_cust_" + orgId;
        log.debug("ManualBillingProvider.createCustomer org={} currency={} -> {}", orgId, currency, ref);
        return ref;
    }

    @Override
    public String recordCharge(String externalCustomerId, UUID invoiceId, BigDecimal amount, String currency) {
        // No capture performed; record the intent locally only.
        String ref = "manual_charge_" + invoiceId;
        log.debug("ManualBillingProvider.recordCharge customer={} invoice={} amount={} {} -> {}",
                externalCustomerId, invoiceId, amount, currency, ref);
        return ref;
    }

    @Override
    public String finalizeInvoice(String externalCustomerId, UUID invoiceId) {
        String ref = "manual_inv_" + invoiceId;
        log.debug("ManualBillingProvider.finalizeInvoice customer={} invoice={} -> {}",
                externalCustomerId, invoiceId, ref);
        return ref;
    }
}
