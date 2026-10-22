package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where an instalment plan stands, from its fixed schedule and its payments:
 * which instalments finance has verified, whether a payment is waiting for
 * finance, and what is still owed. "Paid" means verified (decision 8); one
 * instalment at a time (decision 7). See AGENTS.md, "Instalment plans".
 *
 * @param verified          the instalments finance has verified
 * @param balanceVerified   a pay-off payment was verified: nothing is owed
 * @param awaitingFinance   a succeeded instalment or pay-off not yet verified, if any
 */
public record PlanProgress(PlanSchedule schedule, Set<Integer> verified, boolean balanceVerified,
                           Optional<Payment> awaitingFinance) {

    public static PlanProgress of(PlanSchedule schedule, List<Payment> payments) {
        List<Payment> paid = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getReviewReason() == null)
                .filter(p -> p.getPurpose() == PaymentPurpose.INSTALMENT || p.getPurpose() == PaymentPurpose.BALANCE)
                .toList();
        return new PlanProgress(schedule,
                paid.stream().filter(p -> p.getPurpose() == PaymentPurpose.INSTALMENT && p.getFinanceVerifiedAt() != null)
                        .map(Payment::getInstalmentSequence).collect(Collectors.toSet()),
                paid.stream().anyMatch(p -> p.getPurpose() == PaymentPurpose.BALANCE && p.getFinanceVerifiedAt() != null),
                paid.stream().filter(p -> p.getFinanceVerifiedAt() == null).findFirst());
    }

    /** The instalments not yet verified, in order; none once the balance is paid off. */
    public List<PlanSchedule.Instalment> remaining() {
        return balanceVerified ? List.of()
                : schedule.instalments().stream().filter(i -> !verified.contains(i.sequence())).toList();
    }

    public Optional<PlanSchedule.Instalment> next() {
        return remaining().stream().findFirst();
    }

    /** Everything still owed after the deposit. */
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
}
