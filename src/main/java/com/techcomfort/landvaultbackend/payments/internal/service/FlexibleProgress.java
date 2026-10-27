package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Flexible contribution (3b): where a flexible plan stands — the instalment
 * plan's {@link PlanProgress} with the schedule removed. "Paid" means
 * verified; a payment finance rejected (flagged for refund) doesn't count.
 *
 * @param verified        what finance has verified so far
 * @param awaitingFinance a contribution Paystack confirmed that finance hasn't verified yet, if any
 */
record FlexibleProgress(FlexiblePurchase plan, BigDecimal verified, Optional<Payment> awaitingFinance) {

    static FlexibleProgress of(FlexiblePurchase plan, List<Payment> payments) {
        List<Payment> counted = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getReviewReason() == null)
                .filter(p -> p.getPurpose() == PaymentPurpose.CONTRIBUTION)
                .toList();
        return new FlexibleProgress(plan, counted.stream().filter(p -> p.getFinanceVerifiedAt() != null)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                counted.stream().filter(p -> p.getFinanceVerifiedAt() == null).findFirst());
    }

    /** What is left of the plan total. */
    BigDecimal remaining() {
        return plan.planTotal().subtract(verified).max(BigDecimal.ZERO);
    }

    boolean complete() {
        return remaining().signum() == 0;
    }

    /**
     * Whether {@code amount} may be paid next: no more than what's left, and —
     * unless it finishes the plan — at least the declared minimum contribution.
     */
    boolean allows(BigDecimal amount) {
        boolean finishes = amount.compareTo(remaining()) == 0;
        return amount.signum() > 0 && amount.compareTo(remaining()) <= 0
                && (finishes || plan.minimumContribution() == null
                || amount.compareTo(plan.minimumContribution()) >= 0);
    }
}
