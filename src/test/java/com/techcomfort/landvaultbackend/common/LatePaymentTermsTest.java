package com.techcomfort.landvaultbackend.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LatePaymentTermsTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 22);
    private static final BigDecimal INSTALMENT = new BigDecimal("962500.00");

    /** Top Rank's steps: 5% at 3 months, 10% at 6, 20% at 12 — declared out of order on purpose. */
    private static final LatePaymentTerms TOP_RANK = new LatePaymentTerms(List.of(
            new LatePaymentTerms.Step(6, new BigDecimal("10")), new LatePaymentTerms.Step(3, new BigDecimal("5")),
            new LatePaymentTerms.Step(12, new BigDecimal("20"))), false);

    @Test
    void monthsLateAreWholeMonthsFromTheDueDate() {
        assertThat(LatePaymentTerms.monthsLate(DUE, DUE)).isZero();
        assertThat(LatePaymentTerms.monthsLate(DUE, DUE.minusDays(3))).isZero();
        assertThat(LatePaymentTerms.monthsLate(DUE, DUE.plusMonths(1).minusDays(1))).isZero();
        assertThat(LatePaymentTerms.monthsLate(DUE, DUE.plusMonths(1))).isEqualTo(1);
        assertThat(LatePaymentTerms.monthsLate(DUE, DUE.plusMonths(7).plusDays(10))).isEqualTo(7);
    }

    @Test
    void noPenaltyBeforeTheFirstStepIsReached() {
        assertThat(TOP_RANK.penaltyOn(INSTALMENT, DUE, DUE.plusDays(60))).isEmpty();
    }

    /** Steps replace each other: at 7 months it's 10%, not 5% + 10%. */
    @Test
    void theHighestStepReachedApplies() {
        assertThat(TOP_RANK.penaltyOn(INSTALMENT, DUE, DUE.plusMonths(3))).hasValueSatisfying(p -> {
            assertThat(p.monthsLate()).isEqualTo(3);
            assertThat(p.amount()).isEqualByComparingTo("48125.00");
        });
        assertThat(TOP_RANK.penaltyOn(INSTALMENT, DUE, DUE.plusMonths(7))).hasValueSatisfying(p -> {
            assertThat(p.penaltyPct()).isEqualByComparingTo("10");
            assertThat(p.amount()).isEqualByComparingTo("96250.00");
        });
        assertThat(TOP_RANK.penaltyOn(INSTALMENT, DUE, DUE.plusMonths(30)))
                .hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("192500.00"));
    }

    @Test
    void noStepsMeansNoPenaltyEver() {
        assertThat(LatePaymentTerms.NONE.penaltyOn(INSTALMENT, DUE, DUE.plusYears(5))).isEmpty();
    }

    /** A 0% step reached is no penalty, not a ₦0 line. */
    @Test
    void aZeroStepIsNoPenalty() {
        LatePaymentTerms zero = new LatePaymentTerms(List.of(new LatePaymentTerms.Step(1, BigDecimal.ZERO)), true);
        assertThat(zero.penaltyOn(INSTALMENT, DUE, DUE.plusMonths(2))).isEmpty();
    }
}
