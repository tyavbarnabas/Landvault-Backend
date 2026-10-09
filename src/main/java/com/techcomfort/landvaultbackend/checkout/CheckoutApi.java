package com.techcomfort.landvaultbackend.checkout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout module's surface for other modules. Read-only for now: the
 * payments module reads a transaction to charge for it. Advancing a
 * transaction after payment arrives with the confirmation step.
 */
public interface CheckoutApi {

    /** The buyer's own transaction, or empty — someone else's is indistinguishable from none. */
    Optional<PayableTransaction> transactionForBuyer(UUID transactionId, UUID buyerUserId);

    /**
     * Paystack confirmed the money for this transaction: it moves from
     * {@code pending_payment} straight to {@code awaiting_finance} (decided
     * with the user — nothing happens between the two). Idempotent: returns
     * false, changing nothing, if it had already moved. The plot stays held;
     * it is not sold until finance approves.
     */
    boolean recordPaymentReceived(UUID transactionId, String paymentReference);

    /** Purchases still waiting for payment whose hold ran out before {@code cutoff}. */
    List<UUID> pendingTransactionsWithHoldExpiredBefore(Instant cutoff);

    /**
     * Nothing was paid: the transaction becomes {@code abandoned}, its
     * reservation ends as expired and the plot goes back on sale with its
     * original availability. Once only — false, changing nothing, if the
     * transaction had already moved (paid, or abandoned before).
     */
    boolean abandonTransaction(UUID transactionId);

    /**
     * Read-only: this company's purchase's status (wire value), if the caller
     * can see its plot — empty for another company's or another branch's.
     */
    Optional<String> financeStatusOf(UUID transactionId, UUID tenantId);

    /** FV-2: one company's purchases waiting for finance — within the caller's branch, by row-level security. */
    List<FinanceQueueRow> awaitingFinance(UUID tenantId);

    /**
     * FV-3: allocate — the plot becomes sold, the transaction verified and the
     * reservation converted, together or not at all. Nothing is written unless
     * the result is {@link FinanceDecision#DONE}.
     */
    FinanceDecision verifyAndAllocate(UUID transactionId, UUID tenantId, UUID financeUserId);

    /** The payment didn't check out: the transaction is rejected and the plot goes back on sale. */
    FinanceDecision rejectPayment(UUID transactionId, UUID tenantId, UUID financeUserId, String reason);

    /**
     * TR-1: every verified sale (the seller is owed its agreed price), oldest
     * first. Platform scope only — the plot and estate joins run under the
     * caller's row-level security.
     */
    List<VerifiedSale> verifiedSales();

    /** One verified sale, or empty when it isn't verified (or isn't visible). */
    Optional<VerifiedSale> verifiedSale(UUID transactionId);
}
