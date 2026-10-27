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
        boolean planWithdrawn,
        /** Phase 5b: the developer revoked this plan; a payment may still await finance. */
        boolean planRevoked,
        /**
         * Flexible contribution: the plan total — the most a payment may be.
         * {@code amountDue} is then the least (the minimum to start). Null on
         * every other plan.
         */
        BigDecimal flexiblePlanTotal
) {

    public FinanceQueueRow(UUID transactionId, String transactionReference, UUID estateId, String estateName,
                           UUID plotId, String plotLabel, UUID buyerUserId, BigDecimal totalPrice, Currency currency,
                           Instant awaitingSince, boolean letterApproved, BigDecimal amountDue, String planName) {
        this(transactionId, transactionReference, estateId, estateName, plotId, plotLabel, buyerUserId, totalPrice,
                currency, awaitingSince, letterApproved, amountDue, planName, false, false, null);
    }

    public FinanceQueueRow(UUID transactionId, String transactionReference, UUID estateId, String estateName,
                           UUID plotId, String plotLabel, UUID buyerUserId, BigDecimal totalPrice, Currency currency,
                           Instant awaitingSince, boolean letterApproved, BigDecimal amountDue, String planName,
                           boolean planWithdrawn, boolean planRevoked) {
        this(transactionId, transactionReference, estateId, estateName, plotId, plotLabel, buyerUserId, totalPrice,
                currency, awaitingSince, letterApproved, amountDue, planName, planWithdrawn, planRevoked, null);
    }

    /** A flexible plan: the buyer chose the amount, within {@code amountDue}..{@code flexiblePlanTotal}. */
    public boolean isFlexible() {
        return flexiblePlanTotal != null;
    }

    /** The plan ended early, withdrawn or revoked: the buyer's part of any payment is already owed back. */
    public boolean planEnded() {
        return planWithdrawn || planRevoked;
    }

    /** The purchase's wire status after a plan payment decision. */
    public String planStatus() {
        return planRevoked ? "revoked" : planWithdrawn ? "withdrawn" : "on_plan";
    }

    /** True when the payment being verified holds the plot on a plan (a deposit, or a flexible plan's first payment). */
    public boolean isDeposit() {
        return planName != null;
    }
}
