package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A sale the seller is owed money on. Outright: finance verified it, the plot
 * is sold and the seller is owed {@code totalPrice}. An instalment purchase
 * ({@code escrow} set) is owed from each payment finance verifies, on escrow
 * terms (3c) — from the deposit onwards, not only once the plan completes.
 *
 * @param totalPrice the land price (corner premium included, plan premium not)
 * @param verifiedAt the last status change: verification, or for a plan the
 *                   deposit's verification until it completes
 * @param escrow     null for an outright sale
 */
public record VerifiedSale(
        UUID transactionId,
        String transactionReference,
        UUID sellerTenantId,
        String estateName,
        String plotLabel,
        BigDecimal totalPrice,
        Currency currency,
        Instant verifiedAt,
        Escrow escrow
) {

    /**
     * An instalment purchase's escrow terms, copied when it opened.
     *
     * @param planComplete every payment made and verified: what was held is due
     * @param deductionPct the developer's share; null (with {@code appliesTo})
     *                     when the purchase predates escrow terms — all held
     * @param penaltiesRefundable phase 4: refundable penalties are held like the
     *                     instalment; others are the developer's in full
     * @param withdrawn    phase 5a: the buyer withdrew — what was held goes back
     *                     to them, and the developer is owed only their shares
     * @param revoked      phase 5b: the developer revoked the plan — settled exactly
     *                     as a withdrawal
     */
    public record Escrow(boolean planComplete, BigDecimal deductionPct, RefundAppliesTo appliesTo,
                         boolean penaltiesRefundable, boolean withdrawn, boolean revoked) {

        public Escrow(boolean planComplete, BigDecimal deductionPct, RefundAppliesTo appliesTo,
                      boolean penaltiesRefundable) {
            this(planComplete, deductionPct, appliesTo, penaltiesRefundable, false, false);
        }

        public Escrow(boolean planComplete, BigDecimal deductionPct, RefundAppliesTo appliesTo,
                      boolean penaltiesRefundable, boolean withdrawn) {
            this(planComplete, deductionPct, appliesTo, penaltiesRefundable, withdrawn, false);
        }

        /** The plan ended early, withdrawn or revoked: what was held goes back to the buyer. */
        public boolean ended() {
            return withdrawn || revoked;
        }
    }
}
