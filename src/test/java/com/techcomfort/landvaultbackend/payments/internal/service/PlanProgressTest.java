package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import com.techcomfort.landvaultbackend.payments.internal.domain.PenaltyWaiver;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.ReminderStage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 4: statuses, penalties and reminder stages, worked out for a day — nothing stored. */
class PlanProgressTest {

    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 22);
    private static final LatePaymentTerms STEPS = new LatePaymentTerms(List.of(
            new LatePaymentTerms.Step(1, new BigDecimal("5")), new LatePaymentTerms.Step(3, new BigDecimal("10"))),
            false);

    private static final PlanSchedule SCHEDULE = new PlanSchedule(UUID.randomUUID(), "12-month plan", "monthly",
            new BigDecimal("16500000"), new BigDecimal("1500000"), new BigDecimal("4950000"),
            IntStream.rangeClosed(1, 12).mapToObj(n -> new PlanSchedule.Instalment(n, FIRST_DUE.plusMonths(n - 1),
                    new BigDecimal("962500.00"))).toList(), STEPS);

    @Test
    void anInstalmentMovesFromUpcomingToDueToOverdueToInArrears() {
        PlanProgress nothingPaid = PlanProgress.of(SCHEDULE, List.of());

        assertThat(first(nothingPaid, FIRST_DUE.minusDays(1)).standing()).isEqualTo(PlanProgress.Standing.UPCOMING);
        assertThat(first(nothingPaid, FIRST_DUE).standing()).isEqualTo(PlanProgress.Standing.DUE_TODAY);
        assertThat(first(nothingPaid, FIRST_DUE.plusDays(1)).standing()).isEqualTo(PlanProgress.Standing.OVERDUE);
        assertThat(first(nothingPaid, FIRST_DUE.plusDays(7)).standing()).isEqualTo(PlanProgress.Standing.OVERDUE);
        assertThat(first(nothingPaid, FIRST_DUE.plusDays(8)).standing()).isEqualTo(PlanProgress.Standing.IN_ARREARS);
        assertThat(first(nothingPaid, FIRST_DUE.plusDays(8)).daysLate()).isEqualTo(8);
    }

    /** Paystack confirmed it before the due date; finance verifying weeks later never makes it late. */
    @Test
    void aPaymentAwaitingFinanceIsNeverLate() {
        Payment waiting = payment(PaymentPurpose.INSTALMENT, 1, "962500.00", null);

        PlanProgress.InstalmentStanding s = first(PlanProgress.of(SCHEDULE, List.of(waiting)),
                FIRST_DUE.plusMonths(2));

        assertThat(s.standing()).isEqualTo(PlanProgress.Standing.AWAITING_VERIFICATION);
        assertThat(s.penalty()).isEmpty();
    }

    @Test
    void aPaidInstalmentShowsThePenaltyItWasCharged() {
        Payment late = payment(PaymentPurpose.INSTALMENT, 1, "1010625.00", Instant.now());
        PaymentPenalty line = PaymentPenalty.builder().paymentId(late.getId()).instalmentSequence(1).monthsLate(1)
                .penaltyPct(new BigDecimal("5")).amount(new BigDecimal("48125.00")).build();

        PlanProgress.InstalmentStanding s = first(PlanProgress.of(SCHEDULE, List.of(late), List.of(line)),
                FIRST_DUE.plusMonths(2));

        assertThat(s.standing()).isEqualTo(PlanProgress.Standing.PAID);
        assertThat(s.penalty()).hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("48125"));
    }

    /** Two instalments late, the oldest past a step: arrears totals and the penalty only on the one that reached it. */
    @Test
    void thePlanReadsInArrearsWithTheTotalDueNow() {
        LocalDate today = FIRST_DUE.plusMonths(1).plusDays(9);

        PlanStanding standing = PlanStandings.standing(PlanProgress.of(SCHEDULE, List.of()), today);

        assertThat(standing.status()).isEqualTo("in_arrears");
        assertThat(standing.arrears()).satisfies(a -> {
            assertThat(a.instalmentsLate()).isEqualTo(2);
            assertThat(a.amountOverdue()).isEqualByComparingTo("1925000");
            assertThat(a.penaltyAmount()).isEqualByComparingTo("48125");
            assertThat(a.totalDue()).isEqualByComparingTo("1973125");
            assertThat(a.overdueSince()).isEqualTo(FIRST_DUE);
            assertThat(a.graceEnds()).isEqualTo(FIRST_DUE.plusDays(7));
        });
        assertThat(standing.instalments().get(1).penalty()).isNull();
    }

    @Test
    void aPlanWithNothingLateHasNoArrears() {
        PlanStanding standing = PlanStandings.standing(PlanProgress.of(SCHEDULE, List.of()), FIRST_DUE.minusDays(3));

        assertThat(standing.status()).isEqualTo("on_track");
        assertThat(standing.arrears()).isNull();
    }

    @Test
    void remindersGoSevenDaysAndOneDayBeforeThenOverdueThenInArrears() {
        PlanProgress progress = PlanProgress.of(SCHEDULE, List.of());

        assertThat(stage(progress, FIRST_DUE.minusDays(8))).isNull();
        assertThat(stage(progress, FIRST_DUE.minusDays(7))).isEqualTo(ReminderStage.SEVEN_DAYS_BEFORE);
        assertThat(stage(progress, FIRST_DUE.minusDays(2))).isEqualTo(ReminderStage.SEVEN_DAYS_BEFORE);
        assertThat(stage(progress, FIRST_DUE.minusDays(1))).isEqualTo(ReminderStage.ONE_DAY_BEFORE);
        assertThat(stage(progress, FIRST_DUE.plusDays(1))).isEqualTo(ReminderStage.OVERDUE);
        assertThat(stage(progress, FIRST_DUE.plusDays(8))).isEqualTo(ReminderStage.IN_ARREARS);
        assertThat(stage(PlanProgress.of(SCHEDULE, List.of(payment(PaymentPurpose.INSTALMENT, 1, "962500.00",
                null))), FIRST_DUE.plusDays(8))).as("nothing while finance is verifying it").isNull();
    }

    private static ReminderStage stage(PlanProgress progress, LocalDate today) {
        return InstalmentReminderService.stageFor(progress.standings(today).getFirst(), today).orElse(null);
    }

    private static PlanProgress.InstalmentStanding first(PlanProgress progress, LocalDate today) {
        return progress.standings(today).getFirst();
    }

    /**
     * A waiver is a fixed amount (decision 3): ₦30,000 of a ₦48,125 5% penalty
     * leaves ₦18,125; at three months the 10% step (₦96,250) is owed less the
     * ₦30,000 — ₦66,250. A waiver on another schedule version doesn't count.
     */
    @Test
    void aWaiverComesOffThePenaltyAndAHigherStepIsOwedBeyondIt() {
        PenaltyWaiver waiver = PenaltyWaiver.builder().scheduleVersion(1).instalmentSequence(1)
                .amount(new BigDecimal("30000.00")).build();
        PenaltyWaiver otherVersion = PenaltyWaiver.builder().scheduleVersion(2).instalmentSequence(1)
                .amount(new BigDecimal("1000.00")).build();
        PlanProgress progress = PlanProgress.of(SCHEDULE, List.of(), List.of(), List.of(waiver, otherVersion));
        PlanSchedule.Instalment first = SCHEDULE.instalments().getFirst();

        assertThat(progress.waivedOn(1)).isEqualByComparingTo("30000");
        assertThat(progress.penaltyReached(first, FIRST_DUE.plusMonths(1).plusDays(1)))
                .hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("48125"));
        assertThat(progress.penaltyDue(first, FIRST_DUE.plusMonths(1).plusDays(1)))
                .hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("18125"));
        assertThat(progress.penaltyDue(first, FIRST_DUE.plusMonths(3).plusDays(1)))
                .hasValueSatisfying(p -> assertThat(p.amount()).isEqualByComparingTo("66250"));
        assertThat(PlanProgress.of(SCHEDULE, List.of(), List.of(), List.of(PenaltyWaiver.builder().scheduleVersion(1)
                        .instalmentSequence(1).amount(new BigDecimal("48125.00")).build()))
                .penaltyDue(first, FIRST_DUE.plusMonths(1).plusDays(1))).as("all of it waived: nothing owed").isEmpty();
    }

    private static Payment payment(PaymentPurpose purpose, Integer sequence, String amount, Instant verifiedAt) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .purpose(purpose)
                .instalmentSequence(sequence)
                .amount(new BigDecimal(amount))
                .status(PaymentStatus.SUCCEEDED)
                .financeVerifiedAt(verifiedAt)
                .build();
    }
}
