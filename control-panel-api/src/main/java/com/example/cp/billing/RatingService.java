package com.example.cp.billing;

import com.example.cp.plans.PlanService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a subscription period's metered usage into priced invoice line items.
 *
 * <h2>Price book model</h2>
 * Pricing is read from the subscription's {@code Plan} features (see {@link PlanService#getFeatures}).
 * For a metered feature with key {@code F}, the per-unit price is resolved in this order:
 * <ol>
 *   <li>an explicit feature named {@code "price.F"} whose value is a number (e.g. feature
 *       {@code "price.seats" = 5.00});</li>
 *   <li>otherwise a plan-wide default feature named {@code "price.default"} (a single fallback per-unit
 *       rate applied to every metered feature that has no explicit price);</li>
 *   <li>otherwise the rate is {@code 0} — the line is still emitted (quantity is recorded) but contributes
 *       nothing to the total, so unpriced usage is visible without being charged.</li>
 * </ol>
 * Each line's {@code amount = quantity * unitAmount}, rounded HALF_UP to {@code SCALE} decimal places;
 * the invoice total is the sum of the line amounts. {@code price.*} entries are NOT themselves emitted as
 * usage lines. Lines are ordered by feature key for deterministic output.
 *
 * <p>This class is intentionally pure: {@link #rate} takes the already-gathered usage quantities and the
 * plan id and returns an in-memory {@link RatedInvoice}; it performs no persistence. {@link BillingService}
 * gathers the {@code usage_quotas} rows and persists the result.
 */
@Service
public class RatingService {

    /** Decimal places to which line amounts and the total are rounded. */
    public static final int SCALE = 2;

    /** Prefix marking a plan feature as a per-unit price rather than a metered usage feature. */
    public static final String PRICE_PREFIX = "price.";

    /** Feature key for the plan-wide fallback per-unit rate. */
    public static final String DEFAULT_PRICE_KEY = "price.default";

    private final PlanService planService;

    public RatingService(PlanService planService) {
        this.planService = planService;
    }

    /**
     * Rate the given per-feature consumed quantities against the plan's price book.
     *
     * @param planId           the plan whose features define the price book (may be {@code null} → all rates 0)
     * @param consumedByFeature map of metered {@code feature_key -> consumed_value}; null/negative values
     *                          are treated as zero
     * @return the rated line items and total
     */
    public RatedInvoice rate(UUID planId, Map<String, BigDecimal> consumedByFeature) {
        Map<String, BigDecimal> priceBook = planId == null ? Map.of() : loadPriceBook(planId);
        return rateWithPriceBook(priceBook, consumedByFeature);
    }

    /**
     * Rate against an explicit price book (per-unit rates keyed by metered feature key, plus an optional
     * {@link #DEFAULT_PRICE_KEY}). Exposed primarily for deterministic unit testing of the rating math.
     */
    public RatedInvoice rateWithPriceBook(Map<String, BigDecimal> priceBook,
                                          Map<String, BigDecimal> consumedByFeature) {
        BigDecimal defaultRate = priceBook == null ? null : priceBook.get(DEFAULT_PRICE_KEY);

        // Deterministic ordering by feature key.
        List<String> features = new ArrayList<>(consumedByFeature == null ? List.of() : consumedByFeature.keySet());
        features.removeIf(f -> f == null || f.startsWith(PRICE_PREFIX));
        features.sort(String::compareTo);

        List<RatedLine> lines = new ArrayList<>(features.size());
        BigDecimal total = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

        for (String feature : features) {
            BigDecimal qty = normalizeQuantity(consumedByFeature.get(feature));
            BigDecimal rate = resolveRate(priceBook, defaultRate, feature);
            BigDecimal amount = qty.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
            lines.add(new RatedLine(feature, qty, rate, amount,
                    feature + " usage (" + qty.toPlainString() + " @ " + rate.toPlainString() + ")"));
            total = total.add(amount);
        }
        return new RatedInvoice(List.copyOf(lines), total.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Reads the plan's features and extracts the {@code price.*} entries into a per-feature rate map. The
     * keys are the metered feature keys (the {@code "price."} prefix stripped), plus {@link #DEFAULT_PRICE_KEY}
     * kept verbatim. Non-numeric feature values are ignored.
     */
    public Map<String, BigDecimal> loadPriceBook(UUID planId) {
        Map<String, Object> features = planService.getFeatures(planId);
        Map<String, BigDecimal> book = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : features.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.startsWith(PRICE_PREFIX)) continue;
            BigDecimal rate = toBigDecimal(e.getValue());
            if (rate == null) continue;
            if (DEFAULT_PRICE_KEY.equals(key)) {
                book.put(DEFAULT_PRICE_KEY, rate);
            } else {
                book.put(key.substring(PRICE_PREFIX.length()), rate);
            }
        }
        return book;
    }

    private BigDecimal resolveRate(Map<String, BigDecimal> priceBook, BigDecimal defaultRate, String feature) {
        BigDecimal explicit = priceBook == null ? null : priceBook.get(feature);
        BigDecimal rate = explicit != null ? explicit : (defaultRate != null ? defaultRate : BigDecimal.ZERO);
        return rate.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rate;
    }

    private static BigDecimal normalizeQuantity(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return qty;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** A single rated line before persistence (no id yet). */
    public record RatedLine(String featureKey, BigDecimal quantity, BigDecimal unitAmount,
                            BigDecimal amount, String description) {}

    /** The full rated result: ordered lines + their summed total. */
    public record RatedInvoice(List<RatedLine> lines, BigDecimal total) {}
}
