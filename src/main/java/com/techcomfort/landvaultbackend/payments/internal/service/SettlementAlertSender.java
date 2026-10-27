package com.techcomfort.landvaultbackend.payments.internal.service;

/**
 * Payout alerts. Tells a company's Executive Directors that its payout account is changing
 * (decided with the user): if one of them didn't ask for it, they can say so
 * before a Super Admin approves. Carries no credential and no full account
 * number. Email by default; a logging implementation for tests only.
 */
public interface SettlementAlertSender {

    record AccountSubmittedAlert(String to, String companyName, String bankName, String accountLast4,
                                 String submittedByEmail) {
    }

    /** TR-3: a payout failed, was reversed, or needs a person — sent to LandVault's Super Admins. */
    record PayoutProblemAlert(String to, String companyName, String payoutReference, String part, String amount,
                              String status, String detail) {
    }

    /** A buyer's refund can't be returned until they give a bank account in their own name. */
    record RefundNeedsAccountAlert(String to, String amount, String paymentReference, String purchaseUrl) {
    }

    /** A refund failed or needs a person — sent to LandVault's Super Admins. */
    record RefundProblemAlert(String to, String paymentReference, String amount, String status, String detail) {
    }

    /**
     * Late money resolved: the buyer is told either way (decided with the user).
     * {@code letterNumber}/{@code letterUrl} name the allocation letter when allocated, otherwise null.
     */
    record LatePaymentAlert(String to, String plotLabel, String estateName, String amount, boolean allocated,
                            String reason, String purchaseUrl, String letterNumber, String letterUrl) {
    }

    /**
     * The developer's finance verified (plot allocated) or rejected (money returned) a purchase — sent to the buyer.
     * {@code letterNumber}/{@code letterUrl} name the allocation letter when verified, otherwise null.
     */
    /**
     * {@code planName}: set when the payment verified was a plan's deposit, null otherwise;
     * {@code flexible}: that plan is a flexible one, and the payment its first.
     */
    record PurchaseDecisionAlert(String to, String plotLabel, String estateName, String amount, boolean verified,
                                 String reason, String purchaseUrl, String letterNumber, String letterUrl,
                                 String planName, boolean flexible) {

        PurchaseDecisionAlert(String to, String plotLabel, String estateName, String amount, boolean verified,
                              String reason, String purchaseUrl, String letterNumber, String letterUrl,
                              String planName) {
            this(to, plotLabel, estateName, amount, verified, reason, purchaseUrl, letterNumber, letterUrl, planName,
                    false);
        }
    }

    void accountSubmitted(AccountSubmittedAlert alert);

    void latePaymentResolved(LatePaymentAlert alert);

    void purchaseDecided(PurchaseDecisionAlert alert);

    /**
     * 3b: one instalment verified, the plan still running.
     *
     * @param nextDueDate   e.g. "22 December 2026"; null when nothing is left after this (never expected here)
     */
    record InstalmentAlert(String to, String plotLabel, String estateName, String amount, int instalmentsPaid,
                           int instalmentCount, String nextDueDate, String nextDueAmount, String purchaseUrl) {
    }

    void instalmentVerified(InstalmentAlert alert);

    /** Flexible contribution (3b): a later payment verified — what's paid so far and what's left. Amounts in naira. */
    record ContributionAlert(String to, String plotLabel, String estateName, String amount, String paidSoFar,
                             String remaining, String purchaseUrl) {
    }

    void contributionVerified(ContributionAlert alert);

    /**
     * AR-3, phase 4. {@code stage} is a {@code ReminderStage} name. Amounts are
     * plain naira strings; {@code penalty} is null when none applies yet, and
     * {@code penaltiesDeclared} says whether the purchase has penalty steps at all.
     */
    record InstalmentReminderAlert(String to, String stage, String plotLabel, String estateName, int sequence,
                                   int instalmentCount, String amount, String dueDate, String graceEnds,
                                   String penalty, String totalDue, boolean penaltiesDeclared, String purchaseUrl) {
    }

    void instalmentReminder(InstalmentReminderAlert alert);

    /**
     * Phase 5a: a buyer withdrew from their plan — sent to the buyer, and to
     * the selling company's Executive Directors ({@code toDeveloper}). Amounts
     * are plain naira strings. {@code awaitingFinance}: the developer's share of
     * a payment their finance hasn't verified yet, held until they do; null
     * when there is none. {@code reason}: the buyer's own words, or null.
     */
    record WithdrawalAlert(String to, boolean toDeveloper, String purchaseReference, String planName, String paid,
                           String refund, String developerKeeps, String awaitingFinance, String reason,
                           String purchaseUrl) {
    }

    void planWithdrawn(WithdrawalAlert alert);

    /** Phase 5b: which step of a revocation the buyer is being told about. */
    enum RevocationStage { NOTICE, CANCELLED, CLEARED, REVOKED }

    /**
     * Phase 5b: to the buyer, at each step of a revocation. Amounts are plain
     * naira strings. {@code totalDue}: what is overdue with its penalties, for
     * a notice; {@code refund}/{@code developerKeeps}: what a revocation returns
     * (a notice says what it would). {@code note}: the developer's reason for a
     * notice, or their note on cancelling; may be null.
     */
    record RevocationAlert(String to, RevocationStage stage, String purchaseReference, String planName,
                           String plotLabel, int afterMonths, String deadline, String totalDue, String refund,
                           String developerKeeps, String note, String purchaseUrl) {
    }

    void planRevocation(RevocationAlert alert);

    /** Phase 5c: which step of a restructure someone is being told about. */
    enum RestructureStage { REQUESTED, PROPOSED, REJECTED, WITHDRAWN, LAPSING, LAPSED, ACCEPTED, DECLINED }

    /**
     * Phase 5c: to the buyer at each step, and to the developer's Executive
     * Directors ({@code toDeveloper}) when a request arrives or the buyer
     * answers. {@code terms}: the proposal in plain words, for a proposal or
     * acceptance; {@code note}: the buyer's reason, or the developer's reason
     * or note; {@code expiresOn}: a proposal's last day. Any may be null.
     */
    record RestructureAlert(String to, boolean toDeveloper, RestructureStage stage, String purchaseReference,
                            String planName, String plotLabel, String terms, String note, String expiresOn,
                            String letterNumber, String purchaseUrl) {
    }

    void planRestructure(RestructureAlert alert);

    /**
     * To the buyer: the developer waived (part of) a late-payment penalty.
     * Amounts are plain naira strings; {@code stillOwed} is what is left of
     * that instalment's penalty today ("0.00" when all of it was waived).
     */
    record PenaltyWaivedAlert(String to, String purchaseReference, String planName, String plotLabel,
                              int instalmentSequence, String waived, String stillOwed, String reason,
                              String purchaseUrl) {
    }

    void penaltyWaived(PenaltyWaivedAlert alert);

    void refundNeedsAccount(RefundNeedsAccountAlert alert);

    void refundProblem(RefundProblemAlert alert);

    void payoutProblem(PayoutProblemAlert alert);
}
