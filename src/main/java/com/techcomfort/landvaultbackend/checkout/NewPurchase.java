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
 * @param schedule     the chosen instalment plan and its schedule; null otherwise
 * @param flexible     the chosen flexible plan's terms; null otherwise
 * @param resale       sell my plot: the plot is bought from its present holder; null otherwise
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
        PostalAddress buyerAddress,
        PlanSchedule schedule,
        FlexiblePurchase flexible,
        Resale resale
) {

    /**
     * Sell my plot, phase 5b: who the plot is bought from. The offer names them
     * by initials only, and carries the conditions their own purchase was made
     * on (D29).
     *
     * @param sellerTransactionId the present holder's purchase
     * @param sellerUserId        the present holder
     * @param consented           the estate needed the developer's consent to the transfer, and it was given
     */
    public record Resale(UUID sellerTransactionId, UUID sellerUserId, boolean consented) {
    }
}
