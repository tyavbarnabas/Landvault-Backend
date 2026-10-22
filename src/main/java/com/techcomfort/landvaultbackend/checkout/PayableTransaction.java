package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the payments module needs to know about a transaction to charge for
 * it. {@code totalPrice} is the price agreed at reservation, already rounded to
 * the kobo; {@code status} and {@code plan} are wire values.
 */
public record PayableTransaction(
        UUID id,
        String reference,
        UUID buyerUserId,
        UUID reservationId,
        UUID plotId,
        UUID estateId,
        UUID sellerTenantId,
        BigDecimal totalPrice,
        Currency currency,
        String plan,
        String status,
        /**
         * What the next payment must be: the full price outright, the deposit
         * on an instalment plan. Null for an instalment purchase opened before
         * plans were declared (it has no deposit to charge).
         */
        BigDecimal amountDue
) {

    public boolean isPendingPayment() {
        return "pending_payment".equals(status);
    }

    public boolean isOutright() {
        return "outright".equals(plan);
    }

    public boolean isInstalment() {
        return "installment".equals(plan);
    }
}
