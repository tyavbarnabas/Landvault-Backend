package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A sale finance has verified — the plot is sold and the seller is owed {@code totalPrice}. */
public record VerifiedSale(
        UUID transactionId,
        String transactionReference,
        UUID sellerTenantId,
        String estateName,
        String plotLabel,
        BigDecimal totalPrice,
        Currency currency,
        Instant verifiedAt
) {
}
