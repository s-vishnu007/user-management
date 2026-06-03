package com.example.cp.billing;

import com.example.cp.plans.PlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RatingService}: turning per-feature consumed usage + a plan price book into
 * priced {@link RatingService.RatedLine}s and a summed total. {@link PlanService} is mocked so the price
 * book (the {@code price.*} plan features) is fully controlled; no Spring context or DB is involved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RatingServiceTest {

    @Mock private PlanService planService;
    @InjectMocks private RatingService ratingService;

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void explicitPerFeaturePrices_produceLinesAndTotal() {
        UUID planId = UUID.randomUUID();
        when(planService.getFeatures(planId)).thenReturn(Map.of(
                "price.seats", 5,          // explicit per-unit price for "seats"
                "price.api_calls", "0.01", // string-valued price, parsed to BigDecimal
                "seats", 10                // a non-price feature on the plan is ignored by the rater
        ));

        Map<String, BigDecimal> consumed = new LinkedHashMap<>();
        consumed.put("seats", bd("3"));
        consumed.put("api_calls", bd("1000"));

        RatingService.RatedInvoice rated = ratingService.rate(planId, consumed);

        // Ordered by feature key: api_calls, seats.
        assertThat(rated.lines()).hasSize(2);
        assertThat(rated.lines().get(0).featureKey()).isEqualTo("api_calls");
        assertThat(rated.lines().get(0).amount()).isEqualByComparingTo("10.00"); // 1000 * 0.01
        assertThat(rated.lines().get(1).featureKey()).isEqualTo("seats");
        assertThat(rated.lines().get(1).amount()).isEqualByComparingTo("15.00"); // 3 * 5

        assertThat(rated.total()).isEqualByComparingTo("25.00");
        assertThat(rated.total().scale()).isEqualTo(RatingService.SCALE);
    }

    @Test
    void defaultRate_appliesToFeaturesWithoutExplicitPrice() {
        UUID planId = UUID.randomUUID();
        when(planService.getFeatures(planId)).thenReturn(Map.of(
                "price.default", "2",  // fallback per-unit rate
                "price.seats", "5"     // seats has an explicit override
        ));

        Map<String, BigDecimal> consumed = new LinkedHashMap<>();
        consumed.put("seats", bd("2"));      // 2 * 5 = 10 (explicit wins)
        consumed.put("widgets", bd("4"));    // 4 * 2 = 8  (default applies)

        RatingService.RatedInvoice rated = ratingService.rate(planId, consumed);

        assertThat(rated.lines()).hasSize(2);
        assertThat(lineFor(rated, "seats").amount()).isEqualByComparingTo("10.00");
        assertThat(lineFor(rated, "widgets").amount()).isEqualByComparingTo("8.00");
        assertThat(rated.total()).isEqualByComparingTo("18.00");
    }

    @Test
    void unpricedUsage_emitsZeroAmountLine_butStillRecordsQuantity() {
        UUID planId = UUID.randomUUID();
        when(planService.getFeatures(planId)).thenReturn(Map.of()); // no price book at all

        RatingService.RatedInvoice rated = ratingService.rate(planId, Map.of("seats", bd("7")));

        assertThat(rated.lines()).hasSize(1);
        RatingService.RatedLine line = rated.lines().get(0);
        assertThat(line.featureKey()).isEqualTo("seats");
        assertThat(line.quantity()).isEqualByComparingTo("7");
        assertThat(line.unitAmount()).isEqualByComparingTo("0");
        assertThat(line.amount()).isEqualByComparingTo("0.00");
        assertThat(rated.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void negativeQuantitiesAndRates_areClampedToZero() {
        Map<String, BigDecimal> priceBook = Map.of(
                "seats", bd("-5"),                       // negative rate -> 0
                RatingService.DEFAULT_PRICE_KEY, bd("3") // default applies to widgets
        );
        Map<String, BigDecimal> consumed = new LinkedHashMap<>();
        consumed.put("seats", bd("4"));      // 4 * clamp(-5)=0 -> 0
        consumed.put("widgets", bd("-2"));   // clamp(-2)=0 * 3 -> 0

        RatingService.RatedInvoice rated = ratingService.rateWithPriceBook(priceBook, consumed);

        assertThat(lineFor(rated, "seats").amount()).isEqualByComparingTo("0.00");
        assertThat(lineFor(rated, "widgets").quantity()).isEqualByComparingTo("0");
        assertThat(rated.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void priceFeatures_areNeverEmittedAsUsageLines() {
        Map<String, BigDecimal> priceBook = Map.of("seats", bd("5"));
        Map<String, BigDecimal> consumed = new LinkedHashMap<>();
        consumed.put("seats", bd("1"));
        consumed.put("price.seats", bd("999")); // a stray price.* key in the usage map must be ignored

        RatingService.RatedInvoice rated = ratingService.rateWithPriceBook(priceBook, consumed);

        assertThat(rated.lines()).hasSize(1);
        assertThat(rated.lines().get(0).featureKey()).isEqualTo("seats");
        assertThat(rated.total()).isEqualByComparingTo("5.00");
    }

    @Test
    void noUsage_yieldsEmptyZeroTotalInvoice() {
        UUID planId = UUID.randomUUID();
        when(planService.getFeatures(planId)).thenReturn(Map.of("price.seats", "5"));

        RatingService.RatedInvoice rated = ratingService.rate(planId, Map.of());

        assertThat(rated.lines()).isEmpty();
        assertThat(rated.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void nullPlanId_ratesEverythingAtZero() {
        RatingService.RatedInvoice rated = ratingService.rate(null, Map.of("seats", bd("9")));

        assertThat(rated.lines()).hasSize(1);
        assertThat(rated.total()).isEqualByComparingTo("0.00");
    }

    private static RatingService.RatedLine lineFor(RatingService.RatedInvoice rated, String featureKey) {
        return rated.lines().stream()
                .filter(l -> l.featureKey().equals(featureKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No line for feature " + featureKey));
    }
}
