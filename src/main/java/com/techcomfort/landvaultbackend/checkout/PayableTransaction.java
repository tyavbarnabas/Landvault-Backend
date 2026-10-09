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
        String status
) {

    public boolean isPendingPayment() {
        return "pending_payment".equals(status);
    }

    public boolean isOutright() {
        return "outright".equals(plan);
    }
}
