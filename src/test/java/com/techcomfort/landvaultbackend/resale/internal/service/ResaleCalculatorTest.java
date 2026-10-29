package com.techcomfort.landvaultbackend.resale.internal.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The worked examples in the "Sell my plot" stories, as arithmetic. */
class ResaleCalculatorTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    void aFlexiblePlanPastTheThresholdsSplitsTheGainByTheDeclaredShare() {
        ResaleCalculator.Split s = ResaleCalculator.split(n("7000000"), n("4000000"), n("1500000"), BigDecimal.ZERO,
                n("66.67"), BigDecimal.ZERO);

        assertThat(s.developerBalance()).isEqualByComparingTo("2500000");
        assertThat(s.gain()).isEqualByComparingTo("3000000");
        assertThat(s.sellerReceives()).as("their ₦1.5M back and a third of the gain, rounded down")
                .isEqualByComparingTo("2499900");
        assertThat(s.developerReceives().add(s.sellerReceives())).isEqualByComparingTo("7000000");
    }

    @Test
    void anInstalmentPlanOpenedBeforeGainSharesGivesTheSellerTheWholeGainAndNoPremiumIsOwed() {
        ResaleCalculator.Split s = ResaleCalculator.split(n("18000000"), n("15000000"), n("6000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(s.developerReceives()).isEqualByComparingTo("9000000");
        assertThat(s.sellerReceives()).isEqualByComparingTo("9000000");
    }

    @Test
    void anEarlySaleReturnsExactlyWhatWasPaidLessPenaltiesOwed() {
        ResaleCalculator.Split s = ResaleCalculator.split(n("7000000"), n("5000000"), n("500000"), BigDecimal.ZERO,
                n("100"), BigDecimal.ZERO);
        assertThat(s.sellerReceives()).isEqualByComparingTo("500000");
        assertThat(s.developerReceives()).isEqualByComparingTo("6500000");

        ResaleCalculator.Split late = ResaleCalculator.split(n("7000000"), n("5000000"), n("500000"), n("20000"),
                n("100"), BigDecimal.ZERO);
        assertThat(late.sellerReceives()).as("penalties owed come to the developer").isEqualByComparingTo("480000");
    }

    @Test
    void paidInFullKeepsEverythingButADeclaredFeeEvenBelowTheAgreedPrice() {
        ResaleCalculator.Split s = ResaleCalculator.split(n("18000000"), n("15000000"), n("15000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, n("2"));
        assertThat(s.developerReceives()).isEqualByComparingTo("360000");
        assertThat(s.sellerReceives()).isEqualByComparingTo("17640000");

        ResaleCalculator.Split loss = ResaleCalculator.split(n("12000000"), n("15000000"), n("15000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(loss.gain()).isEqualByComparingTo("-3000000");
        assertThat(loss.sellerReceives()).isEqualByComparingTo("12000000");
    }

    @Test
    void premiumAlreadyPaidStaysWithTheDeveloper() {
        assertThat(ResaleCalculator.landShare(n("4950000"), n("15000000"), n("16500000")))
                .as("a 30% deposit on a plan with a 10% premium").isEqualByComparingTo("4500000");
        assertThat(ResaleCalculator.landShare(n("16500000"), n("15000000"), n("16500000")))
                .as("paid off: exactly the land price").isEqualByComparingTo("15000000");
        assertThat(ResaleCalculator.landShare(n("1000000"), n("15000000"), n("15000000"))).as("no premium")
                .isEqualByComparingTo("1000000");
    }

    @Test
    void bothThresholdsAreNeeded() {
        LocalDate first = LocalDate.of(2026, 4, 28);
        assertThat(ResaleCalculator.minimumPaid(n("4000000.01"))).as("rounded up").isEqualByComparingTo("1200000.01");
        assertThat(ResaleCalculator.thresholdsMet(n("4000000"), n("1200000"), first, LocalDate.of(2026, 10, 28)))
                .isTrue();
        assertThat(ResaleCalculator.thresholdsMet(n("4000000"), n("1199999.99"), first, LocalDate.of(2026, 10, 28)))
                .isFalse();
        assertThat(ResaleCalculator.thresholdsMet(n("4000000"), n("4000000"), first, LocalDate.of(2026, 10, 27)))
                .isFalse();
    }

    @Test
    void aSaleMoreThanHalfAboveTheDevelopersPriceIsReviewed() {
        assertThat(ResaleCalculator.needsReview(n("9000000"), n("6000000"))).isFalse();
        assertThat(ResaleCalculator.needsReview(n("9000000.01"), n("6000000"))).isTrue();
        assertThat(ResaleCalculator.needsReview(n("9000000"), null)).isFalse();
    }
}
