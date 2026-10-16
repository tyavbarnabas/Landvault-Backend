package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PlotIntent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A buyer's paid purchase that finance hasn't decided yet. Not owned: the portfolio shows it as pending verification. */
public record PendingPurchase(
        UUID transactionId,
        UUID plotId,
        UUID estateId,
        String plan,
        PlotIntent intent,
        BigDecimal basePrice,
        BigDecimal cornerPremiumPct,
        BigDecimal totalPrice,
        Currency currency,
        Instant createdAt
) {
}
