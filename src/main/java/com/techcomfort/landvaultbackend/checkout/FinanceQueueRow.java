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
        Instant awaitingSince,
        /** The estate's allocation letter can be issued; when false, verifying is refused until it's approved. */
        boolean letterApproved,
        /** What the payment being verified must be: the full price, or an instalment plan's deposit. */
        BigDecimal amountDue,
        /** The instalment plan's name, or null when paying in full. */
        String planName,
        /** Phase 5a: the buyer withdrew from this plan; a payment may still await finance. */
        boolean planWithdrawn
) {

    public FinanceQueueRow(UUID transactionId, String transactionReference, UUID estateId, String estateName,
                           UUID plotId, String plotLabel, UUID buyerUserId, BigDecimal totalPrice, Currency currency,
                           Instant awaitingSince, boolean letterApproved, BigDecimal amountDue, String planName) {
        this(transactionId, transactionReference, estateId, estateName, plotId, plotLabel, buyerUserId, totalPrice,
                currency, awaitingSince, letterApproved, amountDue, planName, false);
    }

    /** True when the payment being verified is an instalment plan's deposit, not the full price. */
    public boolean isDeposit() {
        return planName != null;
    }
}
