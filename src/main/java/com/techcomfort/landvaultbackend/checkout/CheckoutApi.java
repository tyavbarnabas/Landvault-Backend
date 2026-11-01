package com.techcomfort.landvaultbackend.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
     * 3b: these purchases, if they are this company's and on their plan, as
     * finance-queue rows — read under the caller's RLS, so a branch officer
     * sees only their branch's.
     */
    List<FinanceQueueRow> onPlan(UUID tenantId, Collection<UUID> transactionIds);

    /**
     * 3b: every instalment verified — the plot ON_PLAN becomes SOLD, the
     * purchase verified and the final letter issued, together or not at all.
     */
    FinanceDecision completePlan(UUID transactionId, UUID tenantId, UUID financeUserId);

    /**
     * Phase 5a: the buyer's own running plan ends — the purchase becomes
     * {@code withdrawn} and the plot goes back on sale with its original
     * availability, together or not at all ({@link PlanWithdrawn} is published
     * inside). False, changing nothing, if it isn't theirs or isn't on its plan.
     * The money is the payments module's to settle.
     */
    boolean withdraw(UUID transactionId, UUID buyerUserId, String reason);

    /**
     * Phase 5a: an instalment purchase's escrow terms as copied when it opened,
     * and its land price — read from the purchase alone (no plot join), so a
     * buyer's request can work out a withdrawal. Empty for outright purchases.
     */
    Optional<PlanEscrow> escrowOf(UUID transactionId);

    /** Phase 5a: when the buyer withdrew from this plan, if they did. */
    Optional<Instant> withdrawnAt(UUID transactionId);

    /**
     * Phase 5b: this company's instalment purchase, if the caller can see its
     * plot (row-level security walls another branch's). Any status.
     */
    Optional<PayableTransaction> planForSeller(UUID transactionId, UUID tenantId);

    /**
     * Phase 5b: the developer revokes a running plan — the purchase becomes
     * {@code revoked} and the plot goes back on sale with its original
     * availability, together or not at all ({@link PlanRevoked} is published
     * inside). False, changing nothing, if it isn't this company's or isn't on
     * its plan. The money is the payments module's to settle.
     */
    boolean revoke(UUID transactionId, UUID tenantId, UUID actorUserId, String reason);

    /**
     * Phase 5c: the buyer accepted a restructure — {@code schedule} (the paid
     * instalments as they were, then the new ones) becomes version
     * {@code fromVersion + 1} and is put in force, once only. False, changing
     * nothing, if the purchase isn't the buyer's, isn't on its plan, or its
     * schedule moved on from {@code fromVersion}.
     */
    boolean restructure(UUID transactionId, UUID buyerUserId, int fromVersion, List<PlanSchedule.Instalment> schedule);

    /** Phase 5b: when this plan was revoked, if it was. */
    Optional<Instant> revokedAt(UUID transactionId);

    /** @param landPrice the agreed price, the TOTAL_PRICE basis (plan premium not included) */
    record PlanEscrow(BigDecimal landPrice, VerifiedSale.Escrow escrow) {
    }

    /**
     * Phase 4: running plans (deposit verified, not paid off), under the
     * caller's RLS — one company's, or with a null id and platform scope, all.
     */
    List<RunningPlan> runningPlans(UUID tenantId);

    /** Flexible contribution, phase 4: running flexible plans, the same way. */
    List<RunningPlan> runningFlexiblePlans(UUID tenantId);

    /** Each instalment purchase's plan and fixed schedule, by transaction id; outright purchases have none. */
    Map<UUID, PlanSchedule> schedulesOf(Collection<UUID> transactionIds);

    /** Flexible contribution: each flexible purchase's terms as copied when it opened, by transaction id. */
    Map<UUID, FlexiblePurchase> flexiblePlansOf(Collection<UUID> transactionIds);

    /** OL: the purchase's offer letter; empty for purchases opened before offers existed. */
    Optional<OfferSummary> offerFor(UUID transactionId);

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
     * TR-1: every sale the seller is owed on — verified sales, and (3c)
     * instalment purchases on their plan, paid from escrow — oldest first.
     * Platform scope only — the plot and estate joins run under the caller's
     * row-level security.
     */
    List<VerifiedSale> verifiedSales();

    /** One such sale, or empty when nothing is owed on it yet (or it isn't visible). */
    Optional<VerifiedSale> verifiedSale(UUID transactionId);

    /**
     * Late money: this company's abandoned purchases among {@code transactionIds}
     * that the caller can see (row-level security walls a branch-scoped officer).
     */
    List<AbandonedSale> abandonedSales(UUID tenantId, Collection<UUID> transactionIds);

    /**
     * Late money, allocated (decided with the user): an abandoned purchase that
     * was paid after all gets its plot — sold — and becomes verified, together
     * or not at all, but only while the plot is still for sale. The old hold
     * stays expired, as history.
     */
    FinanceDecision allocateAbandoned(UUID transactionId, UUID tenantId, UUID financeUserId);

    /**
     * The buyer's purchases that are paid and waiting for finance, newest
     * first. The portfolio lists them as pending verification; they become
     * owned plots only when finance verifies ({@link PlotAllocated}).
     */
    List<PendingPurchase> purchasesAwaitingFinance(UUID buyerUserId);

    /**
     * Sell my plot, phase 5a: holds a resale listing for a new buyer, as a
     * reservation that holds the listing rather than the plot. Run inside the
     * caller's transaction, after it has locked the listing. Refuses like a
     * hold: {@code KYC_REQUIRED}, or the estate not available.
     */
    ResaleHoldOpened holdForResale(ResaleHoldRequest request);

    /**
     * Sell my plot (D25): a new buyer holds this purchase's plot now, so the
     * seller can't pay, withdraw or restructure and the developer can't give
     * notice.
     */
    boolean isHeldForResale(UUID transactionId);
}
