package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An abandoned purchase whose money arrived late, as the selling company's
 * finance sees it. {@code plotAvailable}: the plot is still for sale, so it
 * can still be allocated to this buyer; otherwise the only ending is a refund.
 */
public record AbandonedSale(
        UUID transactionId,
        String transactionReference,
        String estateName,
        String plotLabel,
        BigDecimal totalPrice,
        Currency currency,
        boolean plotAvailable
) {
}
