package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One purchase waiting for the developer's finance to verify its payment. */
public record FinanceQueueRow(
        UUID transactionId,
        String transactionReference,
        UUID estateId,
        String estateName,
        UUID plotId,
        String plotLabel,
        UUID buyerUserId,
        BigDecimal totalPrice,
        Currency currency,
        Instant awaitingSince
) {
}
