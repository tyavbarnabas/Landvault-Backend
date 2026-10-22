package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Where an instalment plan stands, from its fixed schedule and its payments:
 * which instalments finance has verified, whether a payment is waiting for
 * finance, what is still owed, and (phase 4) which instalments are overdue or
 * in arrears and what penalty each has reached. "Paid" means verified
 * (decision 8); one instalment at a time (decision 7). Nothing here is
 * stored: it is worked out on every read, for a given day in Lagos. See
 * AGENTS.md, "Instalment plans".
 *
 * @param verified          the instalments finance has verified
 * @param balanceVerified   a pay-off payment was verified: nothing is owed
 * @param awaitingFinance   a succeeded instalment or pay-off not yet verified, if any
 * @param charged           the penalty each instalment was charged, by sequence, on a
 *                          verified or awaiting payment
 */
public record PlanProgress(PlanSchedule schedule, Set<Integer> verified, boolean balanceVerified,
                           Optional<Payment> awaitingFinance, Map<Integer, LatePaymentTerms.Penalty> charged) {

    /** An instalment's standing on a given day. Wire values are lower case. */
    public enum Standing {
        UPCOMING, DUE_TODAY, OVERDUE, IN_ARREARS, AWAITING_VERIFICATION, PAID;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param daysLate days past the due date while unpaid; zero otherwise
     * @param penalty  unpaid: the penalty reached today, which paying now would
     *                 charge; paid or awaiting: the penalty charged with it
     */
    public record InstalmentStanding(PlanSchedule.Instalment instalment, Standing standing, int daysLate,
                                     Optional<LatePaymentTerms.Penalty> penalty) {

        public boolean late() {
            return standing == Standing.OVERDUE || standing == Standing.IN_ARREARS;
        }
    }

    public static PlanProgress of(PlanSchedule schedule, List<Payment> payments) {
        return of(schedule, payments, List.of());
    }

    /** {@code penalties}: the penalty lines of any of {@code payments}; others are ignored. */
    public static PlanProgress of(PlanSchedule schedule, List<Payment> payments, Collection<PaymentPenalty> penalties) {
        List<Payment> paid = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getReviewReason() == null)
                .filter(p -> p.getPurpose() == PaymentPurpose.INSTALMENT || p.getPurpose() == PaymentPurpose.BALANCE)
                .toList();
        Set<UUID> counted = paid.stream().map(Payment::getId).collect(Collectors.toSet());
        Map<Integer, LatePaymentTerms.Penalty> charged = new HashMap<>();
        penalties.stream().filter(line -> counted.contains(line.getPaymentId()))
                .forEach(line -> charged.put(line.getInstalmentSequence(), new LatePaymentTerms.Penalty(
                        line.getMonthsLate(), line.getPenaltyPct(), line.getAmount())));
        return new PlanProgress(schedule,
                paid.stream().filter(p -> p.getPurpose() == PaymentPurpose.INSTALMENT && p.getFinanceVerifiedAt() != null)
                        .map(Payment::getInstalmentSequence).collect(Collectors.toSet()),
                paid.stream().anyMatch(p -> p.getPurpose() == PaymentPurpose.BALANCE && p.getFinanceVerifiedAt() != null),
                paid.stream().filter(p -> p.getFinanceVerifiedAt() == null).findFirst(), Map.copyOf(charged));
    }

    /** The instalments not yet verified, in order; none once the balance is paid off. */
    public List<PlanSchedule.Instalment> remaining() {
        return balanceVerified ? List.of()
                : schedule.instalments().stream().filter(i -> !verified.contains(i.sequence())).toList();
    }

    public Optional<PlanSchedule.Instalment> next() {
        return remaining().stream().findFirst();
    }

    /** Everything still owed after the deposit, penalties aside. */
    public BigDecimal balance() {
        return remaining().stream().map(PlanSchedule.Instalment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean complete() {
        return remaining().isEmpty();
    }

    /** How many instalments are paid: all of them once the balance is paid off. */
    public int instalmentsPaid() {
        return balanceVerified ? schedule.instalments().size() : verified.size();
    }

    /** The penalty an unpaid instalment has reached on {@code today}, if any. */
    public Optional<LatePaymentTerms.Penalty> penaltyDue(PlanSchedule.Instalment instalment, LocalDate today) {
        return schedule.lateTerms().penaltyOn(instalment.amount(), instalment.dueDate(), today);
    }

    /** Every instalment's standing on {@code today}. A paid-off plan's are all paid. */
    public List<InstalmentStanding> standings(LocalDate today) {
        Set<Integer> awaiting = awaitingFinance.map(p -> p.getPurpose() == PaymentPurpose.BALANCE
                ? remaining().stream().map(PlanSchedule.Instalment::sequence).collect(Collectors.toSet())
                : Set.of(p.getInstalmentSequence())).orElse(Set.of());
        Set<Integer> unpaid = remaining().stream().map(PlanSchedule.Instalment::sequence).collect(Collectors.toSet());
        return schedule.instalments().stream().map(i -> {
            if (!unpaid.contains(i.sequence())) {
                return new InstalmentStanding(i, Standing.PAID, 0, Optional.ofNullable(charged.get(i.sequence())));
            }
            if (awaiting.contains(i.sequence())) {
                // Paystack confirmed it; a finance delay never makes it late.
                return new InstalmentStanding(i, Standing.AWAITING_VERIFICATION, 0,
                        Optional.ofNullable(charged.get(i.sequence())));
            }
            long days = ChronoUnit.DAYS.between(i.dueDate(), today);
            Standing standing = days < 0 ? Standing.UPCOMING : days == 0 ? Standing.DUE_TODAY
                    : days <= LatePaymentTerms.GRACE_DAYS ? Standing.OVERDUE : Standing.IN_ARREARS;
            return new InstalmentStanding(i, standing, (int) Math.max(days, 0), penaltyDue(i, today));
        }).toList();
    }
}
