package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/** Everything a receipt prints, taken from its stored copy. */
public record ReceiptContent(
        String number,
        Instant issuedAt,
        String buyerName,
        String sellerName,
        String sellerRcNumber,
        String estateName,
        String estateLocation,
        String plotLabel,
        BigDecimal plotSizeSqm,
        boolean corner,
        BigDecimal amount,
        Currency currency,
        String channel,
        String last4,
        String paymentReference,
        Instant paidAt,
        String verificationCode,
        String verifyUrl
) {
}
