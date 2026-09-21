package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.inventory.internal.service.PlotPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corner-premium and per-square-metre arithmetic, away from HTTP.
 * <p>
 * Worth its own unit test rather than leaning on the integration test: these
 * are the only computed money figures in the module, nothing stores them to
 * check against, and a rounding mistake here produces a number that looks
 * entirely plausible on a price list.
 */
class PlotPricingTests {

    private static final BigDecimal TIER_PRICE = new BigDecimal("20000000.0000");

    @Test
    void aNonCornerPlotIsTheTierPriceExactly() {
        assertThat(PlotPricing.price(TIER_PRICE, false, new BigDecimal("10.00")))
                .as("the premium must not apply to a plot that is not a corner")
                .isSameAs(TIER_PRICE);
    }

    @Test
    void aCornerPlotAddsThePremium() {
        assertThat(PlotPricing.price(TIER_PRICE, true, new BigDecimal("10.00")))
                .isEqualByComparingTo("22000000");
        assertThat(PlotPricing.price(TIER_PRICE, true, new BigDecimal("7.50")))
                .isEqualByComparingTo("21500000");
    }

    /**
     * A premium that divides into a repeating decimal. Done in
     * {@code double} this drifts; in {@link BigDecimal} it lands on an exact
     * figure a buyer can be invoiced for.
     */
    @Test
    void aRepeatingPremiumStillProducesAnExactFigure() {
        BigDecimal price = PlotPricing.price(new BigDecimal("85000000.0000"), true, new BigDecimal("3.33"));

        assertThat(price).isEqualByComparingTo("87830500");
        assertThat(price.scale()).as("kept at the money scale of price_tiers.price").isEqualTo(4);
    }

    @Test
    void aZeroOrAbsentPremiumLeavesTheTierPriceUntouched() {
        assertThat(PlotPricing.price(TIER_PRICE, true, null)).isSameAs(TIER_PRICE);
        assertThat(PlotPricing.price(TIER_PRICE, true, BigDecimal.ZERO)).isSameAs(TIER_PRICE);
    }

    @Test
    void pricePerSqmDividesThePriceByTheNominalSize() {
        assertThat(PlotPricing.pricePerSqm(TIER_PRICE, new BigDecimal("250.00")))
                .isEqualByComparingTo("80000.00");
    }

    /**
     * The no-fabricated-data rule, in arithmetic form: a unit-type plot has
     * no land area, so it has no rate. Zero would render as a free plot.
     */
    @Test
    void pricePerSqmIsAbsentRatherThanZeroWhenThereIsNoSizeToDivideBy() {
        assertThat(PlotPricing.pricePerSqm(TIER_PRICE, null)).isNull();
        assertThat(PlotPricing.pricePerSqm(TIER_PRICE, BigDecimal.ZERO)).isNull();
        assertThat(PlotPricing.pricePerSqm(null, new BigDecimal("250.00"))).isNull();
    }

    /**
     * The rate is derived from the price, never the reverse — larger plots
     * are routinely discounted per square metre, so pricing off a rate would
     * quietly overcharge every large plot. See {@code PriceTier.price}.
     */
    @Test
    void largerTiersLegitimatelyCarryALowerRatePerSquareMetre() {
        BigDecimal small = PlotPricing.pricePerSqm(new BigDecimal("3240000"), new BigDecimal("180"));
        BigDecimal large = PlotPricing.pricePerSqm(new BigDecimal("8700000"), new BigDecimal("600"));

        assertThat(small).isEqualByComparingTo("18000.00");
        assertThat(large).isEqualByComparingTo("14500.00");
        assertThat(large).isLessThan(small);
    }
}
