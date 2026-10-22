package com.techcomfort.landvaultbackend.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InstalmentPlanCalculatorTest {

    /** The example agreed with the user: the premium on the whole price, the deposit a share of that total. */
    @Test
    void theAgreedExampleWorksOutExactly() {
        InstalmentPlanCalculator.Quote quote = InstalmentPlanCalculator.quote(new BigDecimal("15000000"),
                new InstalmentPlanCalculator.Terms(new BigDecimal("30"), null, 12, PaymentFrequency.MONTHLY,
                        new BigDecimal("10"))).orElseThrow();

        assertThat(quote.planTotal()).isEqualByComparingTo("16500000");
        assertThat(quote.premium()).isEqualByComparingTo("1500000");
        assertThat(quote.deposit()).isEqualByComparingTo("4950000");
        assertThat(quote.instalment()).isEqualByComparingTo("962500");
        assertThat(quote.lastInstalment()).isEqualByComparingTo("962500");
    }

    @Test
    void leftoverKoboGoesOnTheLastInstalmentAndEverythingAddsUp() {
        InstalmentPlanCalculator.Quote quote = InstalmentPlanCalculator.quote(new BigDecimal("10000000"),
                new InstalmentPlanCalculator.Terms(new BigDecimal("30"), null, 3, PaymentFrequency.QUARTERLY,
                        null)).orElseThrow();

        assertThat(quote.instalment()).isEqualByComparingTo("2333333.33");
        assertThat(quote.lastInstalment()).isEqualByComparingTo("2333333.34");
        assertThat(quote.deposit().add(quote.instalments().stream().reduce(BigDecimal.ZERO, BigDecimal::add)))
                .isEqualByComparingTo(quote.planTotal());
        assertThat(quote.premium()).isEqualByComparingTo("0");
    }

    @Test
    void aFixedDepositThatLeavesNothingToPayIsNotOffered() {
        InstalmentPlanCalculator.Terms terms = new InstalmentPlanCalculator.Terms(null, new BigDecimal("3000000"), 6,
                PaymentFrequency.MONTHLY, BigDecimal.ZERO);

        assertThat(InstalmentPlanCalculator.quote(new BigDecimal("2500000"), terms)).isEmpty();
        assertThat(InstalmentPlanCalculator.quote(new BigDecimal("6000000"), terms).orElseThrow().instalment())
                .isEqualByComparingTo("500000");
    }

    @Test
    void aPenaltyIsAShareOfOneInstalment() {
        assertThat(InstalmentPlanCalculator.penaltyOn(new BigDecimal("1000000"), new BigDecimal("5")))
                .isEqualByComparingTo("50000");
    }

    @Test
    void dueDatesCountFromTheOfferDateAndShortMonthsDontDragTheRest() {
        assertThat(InstalmentPlanCalculator.dueDates(LocalDate.of(2027, 1, 31), PaymentFrequency.MONTHLY, 3))
                .containsExactly(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
        assertThat(InstalmentPlanCalculator.dueDates(LocalDate.of(2026, 10, 22), PaymentFrequency.QUARTERLY, 2))
                .containsExactly(LocalDate.of(2027, 1, 22), LocalDate.of(2027, 4, 22));
    }
}
