package com.techcomfort.landvaultbackend.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FlexiblePlanCalculatorTest {

    private static FlexiblePlanCalculator.Terms terms(String startPct, String premiumPct, String deductionPct,
                                                      RefundAppliesTo appliesTo, String developerGainPct) {
        return new FlexiblePlanCalculator.Terms(new BigDecimal(startPct), new BigDecimal(premiumPct),
                new BigDecimal("100000"), new BigDecimal(deductionPct), appliesTo, new BigDecimal(developerGainPct),
                6, 30, 36);
    }

    /** The stories' example: ₦4M, 15% kept on what was paid — stop after ₦2M, get ₦1.7M back; 60% of any gain. */
    @Test
    void theStoriesExampleWorksOutInNaira() {
        FlexiblePlanCalculator.Quote quote = FlexiblePlanCalculator.quote(new BigDecimal("4000000"),
                terms("20", "0", "15", RefundAppliesTo.AMOUNT_PAID, "40"));

        assertThat(quote.planTotal()).isEqualByComparingTo("4000000");
        assertThat(quote.minimumToStart()).isEqualByComparingTo("800000");
        assertThat(quote.ifYouStopAfterPaying()).extracting(FlexiblePlanCalculator.Exit::paid)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("800000"),
                        new BigDecimal("2000000"));
        FlexiblePlanCalculator.Exit half = quote.ifYouStopAfterPaying().getLast();
        assertThat(half.youGetBack()).isEqualByComparingTo("1700000");
        assertThat(half.developerKeeps()).isEqualByComparingTo("300000");
        assertThat(quote.ifSoldForMore().buyerSharePct()).isEqualByComparingTo("60");
        assertThat(quote.ifSoldForMore().perMillionAbove()).as("₦6M resale: ₦2M above, ₦1.2M to the buyer")
                .isEqualByComparingTo("600000");
        FlexiblePlanCalculator.GainExample example = quote.ifSoldForMore().example();
        assertThat(example.risePct()).isEqualByComparingTo("20");
        assertThat(example.soldFor()).isEqualByComparingTo("4800000");
        assertThat(example.difference()).isEqualByComparingTo("800000");
        assertThat(example.youGet()).isEqualByComparingTo("480000");
        assertThat(quote.maxMonths()).isEqualTo(36);
    }

    /** A fixed sum on the agreed price is kept from the first payments, never more than was paid. */
    @Test
    void aFixedSumOnTheAgreedPriceIsKeptFromTheFirstPayments() {
        FlexiblePlanCalculator.Terms fixed = terms("10", "0", "10", RefundAppliesTo.TOTAL_PRICE, "100");

        assertThat(FlexiblePlanCalculator.exit(new BigDecimal("300000"), new BigDecimal("4000000"), fixed)
                .youGetBack()).as("less than the ₦400,000 sum paid: nothing comes back").isEqualByComparingTo("0");
        FlexiblePlanCalculator.Exit later = FlexiblePlanCalculator.exit(new BigDecimal("2000000"),
                new BigDecimal("4000000"), fixed);
        assertThat(later.developerKeeps()).isEqualByComparingTo("400000");
        assertThat(later.youGetBack()).isEqualByComparingTo("1600000");
        FlexiblePlanCalculator.Gain keepsAll = FlexiblePlanCalculator.quote(new BigDecimal("4000000"), fixed)
                .ifSoldForMore();
        assertThat(keepsAll.perMillionAbove()).as("the developer keeps all of any gain").isEqualByComparingTo("0");
        assertThat(keepsAll.example().youGet()).isEqualByComparingTo("0");
    }

    /**
     * The premium is on the whole price, but the minimum to start is a share
     * of the land price (decision 1) — unlike an instalment deposit.
     */
    @Test
    void thePremiumIsOnTheWholePriceButTheMinimumToStartIsOnTheLandPrice() {
        FlexiblePlanCalculator.Quote quote = FlexiblePlanCalculator.quote(new BigDecimal("4500000"),
                terms("20", "10", "15", RefundAppliesTo.AMOUNT_PAID, "40"));

        assertThat(quote.planTotal()).isEqualByComparingTo("4950000");
        assertThat(quote.premium()).isEqualByComparingTo("450000");
        assertThat(quote.minimumToStart()).as("20% of ₦4.5M, not of ₦4.95M").isEqualByComparingTo("900000");
        assertThat(FlexiblePlanCalculator.exit(new BigDecimal("1000000.01"), quote.price(),
                terms("20", "10", "15", RefundAppliesTo.AMOUNT_PAID, "40")).developerKeeps())
                .as("150,000.0015 rounds down: the developer never keeps a kobo more").isEqualByComparingTo("150000.00");
    }
}
