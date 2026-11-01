package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sell my plot, phase 5a: the resale module asks checkout to hold a listing for
 * a new buyer. The price is the resale's, fixed by the resale module when the
 * buyer holds it; checkout never prices a resale itself.
 *
 * @param sellerTransactionId the seller's purchase, whose due dates pause while the hold is open
 * @param consented           the estate needed the developer's consent to the transfer, and it was given
 * @param developerReceives   the developer's part of {@code price}, fixed now (D25)
 * @param sellerReceives      the seller's part; the two add up to {@code price}
 */
public record ResaleHoldRequest(UUID buyerUserId, UUID listingId, UUID sellerTransactionId, UUID plotId,
                                UUID estateId, UUID sellerTenantId, BigDecimal price, Currency currency,
                                boolean consented, BigDecimal developerReceives, BigDecimal sellerReceives) {
}
