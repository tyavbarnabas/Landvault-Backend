package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 5c: what a restructure proposes — the total owed never changes; penalties already reached are kept. */
class RestructureFiguresTest {

    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 22);
    private static final LatePaymentTerms FIVE_PCT_AT_ONE_MONTH = new LatePaymentTerms(
            List.of(new LatePaymentTerms.Step(1, new BigDecimal("5"))), false);
    private static final PlanSchedule SCHEDULE = new PlanSchedule(UUID.randomUUID(), "12-month plan", "monthly",
            new BigDecimal("16500000"), new BigDecimal("1500000"), new BigDecimal("4950000"),
            IntStream.rangeClosed(1, 12).mapToObj(n -> new PlanSchedule.Instalment(n, FIRST_DUE.plusMonths(n - 1),
                    new BigDecimal("962500.00"))).toList(), FIVE_PCT_AT_ONE_MONTH);

    /**
     * Two paid, the third 45 days late (one month: 5%), the fourth 14 days
     * late (no step yet): the ten unpaid (₦9,625,000) are spread over 18,
     * numbered on from 3; the third's 5% (₦48,125) rides on the first new one.
     * Nothing is owed less.
     */
    @Test
    void theBalanceIsSpreadUnchangedAndThePenaltyReachedIsCarried() {
        LocalDate today = FIRST_DUE.plusMonths(2).plusDays(45);
        PlanProgress progress = PlanProgress.of(SCHEDULE, List.of(verified(1), verified(2)));

        RestructureService.Figures f = RestructureService.figures(SCHEDULE, progress, today, 18,
                PaymentFrequency.MONTHLY, today.plusDays(10));

        assertThat(f.paid()).isEqualTo(2);
        assertThat(f.balance()).isEqualByComparingTo("9625000");
        assertThat(f.fresh()).hasSize(18);
        assertThat(f.fresh().getFirst().sequence()).as("numbering carries on").isEqualTo(3);
        assertThat(f.fresh().getLast().sequence()).isEqualTo(20);
        assertThat(f.fresh().stream().map(PlanSchedule.Instalment::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the total owed is unchanged").isEqualByComparingTo(f.balance());
        assertThat(f.carried()).as("only instalment 3 has reached a step").isEqualByComparingTo("48125");
        assertThat(f.fresh().getFirst().carriedPenalty()).isEqualByComparingTo("48125");
        assertThat(f.fresh().get(1).carriedPenalty()).isEqualByComparingTo("0");
        assertThat(f.fresh().get(1).dueDate()).isEqualTo(today.plusDays(10).plusMonths(1));
    }

    @Test
    void hometruesLimitsApply() {
        LocalDate today = FIRST_DUE.plusDays(3);
        PlanProgress progress = PlanProgress.of(SCHEDULE, List.of(verified(1)));

        assertThatThrownBy(() -> RestructureService.figures(SCHEDULE, progress, today, 60, PaymentFrequency.MONTHLY,
                today.plusDays(1))).isInstanceOf(PaymentException.InvalidRestructureTerms.class)
                .hasMessageContaining("between 1 and 59");
        assertThatThrownBy(() -> RestructureService.figures(SCHEDULE, progress, today, 12, PaymentFrequency.MONTHLY,
                today)).as("not today").isInstanceOf(PaymentException.InvalidRestructureTerms.class);
        assertThatThrownBy(() -> RestructureService.figures(SCHEDULE, progress, today, 12, PaymentFrequency.MONTHLY,
                today.plusMonths(3).plusDays(1))).as("within 3 months")
                .isInstanceOf(PaymentException.InvalidRestructureTerms.class);
        assertThat(RestructureService.figures(SCHEDULE, progress, today, 59, PaymentFrequency.QUARTERLY,
                today.plusMonths(3)).fresh()).hasSize(59);
    }

    @Test
    void aPaymentWaitingForFinanceMustBeSettledFirst() {
        Payment waiting = payment(2, null);

        assertThatThrownBy(() -> RestructureService.figures(SCHEDULE,
                PlanProgress.of(SCHEDULE, List.of(verified(1), waiting)), FIRST_DUE, 6, PaymentFrequency.MONTHLY,
                FIRST_DUE.plusDays(5))).isInstanceOf(PaymentException.PaymentAwaitingFinance.class);
    }

    /** A carried penalty is charged with its instalment, with any penalty that instalment reaches itself. */
    @Test
    void aCarriedPenaltyIsChargedWithItsInstalment() {
        PlanSchedule restructured = new PlanSchedule(UUID.randomUUID(), "12-month plan", "monthly",
                new BigDecimal("16500000"), new BigDecimal("1500000"), new BigDecimal("4950000"),
                List.of(new PlanSchedule.Instalment(1, FIRST_DUE, new BigDecimal("962500.00"), new BigDecimal("48125"))),
                FIVE_PCT_AT_ONE_MONTH, 2);
        PlanProgress progress = PlanProgress.of(restructured, List.of());
        PlanSchedule.Instalment first = restructured.instalments().getFirst();

        assertThat(progress.penaltyDue(first, FIRST_DUE.minusDays(1)))
                .hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("48125"));
        assertThat(progress.penaltyDue(first, FIRST_DUE.plusMonths(1)))
                .as("late again: its own 5% on top of what was carried")
                .hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("96250"));
    }

    @Test
    void splitPutsTheLeftoverKoboOnTheLast() {
        assertThat(InstalmentPlanCalculator.split(new BigDecimal("1000000.00"), 3)).hasValueSatisfying(parts ->
                assertThat(parts).extracting(BigDecimal::toPlainString)
                        .containsExactly("333333.33", "333333.33", "333333.34"));
        assertThat(InstalmentPlanCalculator.dueDatesFrom(LocalDate.of(2027, 1, 31), PaymentFrequency.MONTHLY, 3))
                .containsExactly(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31));
    }

    private static Payment verified(int sequence) {
        return payment(sequence, Instant.now());
    }

    private static Payment payment(int sequence, Instant verifiedAt) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .purpose(PaymentPurpose.INSTALMENT)
                .instalmentSequence(sequence)
                .amount(new BigDecimal("962500.00"))
                .status(PaymentStatus.SUCCEEDED)
                .financeVerifiedAt(verifiedAt)
                .build();
    }
}
