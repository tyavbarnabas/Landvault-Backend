package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PostalAddress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A purchase just opened ("Proceed to buy"), as its offer letter needs it:
 * the price captured on the hold and the address copied from the account.
 *
 * @param reservedAt   when the buyer reserved the plot — the offer's application date
 * @param buyerAddress null only for accounts from before addresses were collected
 */
public record NewPurchase(
        UUID transactionId,
        UUID buyerUserId,
        UUID plotId,
        UUID estateId,
        UUID sellerTenantId,
        BigDecimal basePrice,
        BigDecimal cornerPremiumPct,
        BigDecimal totalPrice,
        Currency currency,
        Instant reservedAt,
        PostalAddress buyerAddress
) {
}
