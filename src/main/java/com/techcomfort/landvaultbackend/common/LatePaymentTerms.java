package com.techcomfort.landvaultbackend.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The late-payment terms an instalment purchase was offered (phase 4), and
 * the one place lateness and penalties are worked out from them. HomeTrue's
 * rules, the same for every developer: an instalment is overdue the day after
 * its due date and in arrears after {@link #GRACE_DAYS} days; months late are
 * whole months from the due date; the highest declared step reached applies
 * (steps replace each other, they don't add up); the penalty is that step's
 * share of the overdue instalment. No steps: no penalty. See AGENTS.md,
 * "Instalment plans".
 *
 * @param steps               months late → % of the instalment, as declared
 * @param penaltiesRefundable declared by the developer: whether penalties come
 *                            back, on the refund terms, to a buyer who withdraws
 * @param revocation          phase 5b: when the plan may be revoked; null when no
 *                            default terms were declared (not revocable)
 */
public record LatePaymentTerms(List<Step> steps, boolean penaltiesRefundable, RevocationTerms revocation) {

    /** Days after the due date before an unpaid instalment is in arrears. */
    public static final int GRACE_DAYS = 7;

    public static final LatePaymentTerms NONE = new LatePaymentTerms(List.of(), true, null);

    public LatePaymentTerms {
        steps = steps.stream().sorted(Comparator.comparingInt(Step::monthsLate)).toList();
    }

    public LatePaymentTerms(List<Step> steps, boolean penaltiesRefundable) {
        this(steps, penaltiesRefundable, null);
    }

    public record Step(int monthsLate, BigDecimal penaltyPct) {
    }

    /** A penalty reached: the step and its amount on the instalment. */
    public record Penalty(int monthsLate, BigDecimal penaltyPct, BigDecimal amount) {
    }

    /** Whole months from the due date to {@code on}; zero before a month has passed. */
    public static int monthsLate(LocalDate dueDate, LocalDate on) {
        return on.isAfter(dueDate) ? (int) ChronoUnit.MONTHS.between(dueDate, on) : 0;
    }

    /** The penalty on an instalment unpaid on {@code on}, if a step has been reached and it comes to something. */
    public Optional<Penalty> penaltyOn(BigDecimal instalment, LocalDate dueDate, LocalDate on) {
        int months = monthsLate(dueDate, on);
        return steps.stream()
                .filter(step -> step.monthsLate() <= months)
                .reduce((lower, higher) -> higher)
                .map(step -> new Penalty(step.monthsLate(), step.penaltyPct(),
                        InstalmentPlanCalculator.penaltyOn(instalment, step.penaltyPct())))
                .filter(penalty -> penalty.amount().signum() > 0);
    }
}
