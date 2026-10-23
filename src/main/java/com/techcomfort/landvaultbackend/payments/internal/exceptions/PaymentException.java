package com.techcomfort.landvaultbackend.payments.internal.exceptions;

import java.time.LocalDate;

/** Payment refusals. Never carries a secret key or card detail. */
public abstract class PaymentException extends RuntimeException {

    protected PaymentException(String message) {
        super(message);
    }

    /** Paystack can only be asked for NGN in this slice; diaspora rails are out of scope. */
    public static class UnsupportedCurrency extends PaymentException {
        public UnsupportedCurrency(String currency) {
            super("Payments in " + currency + " aren't supported yet. Only NGN can be paid online for now.");
        }
    }

    /** Zero, negative, or finer than a kobo — none can be charged. */
    public static class InvalidAmount extends PaymentException {
        public InvalidAmount(String reason) {
            super(reason);
        }
    }

    /** Paystack said no — a bad request, or a reference it won't accept. Carries Paystack's own message. */
    public static class GatewayRefused extends PaymentException {
        private final String paystackMessage;

        public GatewayRefused(String paystackMessage) {
            super("Paystack refused the request: " + paystackMessage);
            this.paystackMessage = paystackMessage;
        }

        /** Paystack's own words, without our prefix — what gets stored and shown. */
        public String paystackMessage() {
            return paystackMessage;
        }
    }

    /** Paystack couldn't be reached, or failed on its side. Safe to try again. */
    public static class GatewayUnavailable extends PaymentException {
        public GatewayUnavailable() {
            super("The payment provider isn't responding right now. Please try again in a moment.");
        }
    }

    /** Not this buyer's transaction, or none at all — deliberately the same answer. */
    public static class TransactionNotFound extends PaymentException {
        public TransactionNotFound() {
            super("Transaction not found.");
        }
    }

    /** Already paid for, verified, or rejected — nothing left to pay. */
    public static class TransactionNotPayable extends PaymentException {
        public TransactionNotPayable(String status) {
            super("This transaction can't be paid for now (it is " + status + ").");
        }
    }

    /** OL: the buyer hasn't accepted the purchase's offer letter yet. */
    public static class OfferNotAccepted extends PaymentException {
        public OfferNotAccepted() {
            super("Accept your offer letter before paying.");
        }
    }

    /** 3b, decision 7: the next instalment only once the current one is verified. */
    public static class InstalmentAwaitingVerification extends PaymentException {
        public InstalmentAwaitingVerification() {
            super("Your last payment is waiting for the developer's finance team to verify it. You can pay the next "
                    + "one once it's verified.");
        }
    }

    /** "Pay off the balance" only applies to a plan whose deposit is verified. */
    public static class NothingToPayOff extends PaymentException {
        public NothingToPayOff() {
            super("There is no plan balance to pay off on this purchase.");
        }
    }

    /** Installments need saved-card charging, which isn't built yet. */
    public static class PlanNotSupported extends PaymentException {
        public PlanNotSupported(String plan) {
            super("Online payment is only available for outright purchases for now, not '" + plan + "'.");
        }
    }

    /** No such payment, or not this buyer's — deliberately the same answer. */
    public static class PaymentNotFound extends PaymentException {
        public PaymentNotFound() {
            super("Payment not found.");
        }
    }

    /** A webhook that isn't signed by Paystack with our key. */
    public static class WebhookSignatureInvalid extends PaymentException {
        public WebhookSignatureInvalid() {
            super("Invalid webhook signature.");
        }
    }

    /** Finance and payout accounts belong to one company; a caller with no company (a buyer, platform staff) can't act. */
    public static class CompanyScopeRequired extends PaymentException {
        public CompanyScopeRequired() {
            super("This is done by the company's own staff, signed in to their company.");
        }
    }

    public static class NotAwaitingFinance extends PaymentException {
        public NotAwaitingFinance() {
            super("This purchase isn't waiting for finance — it was already decided, or was never paid.");
        }
    }

    public static class PlotNotReserved extends PaymentException {
        public PlotNotReserved() {
            super("The plot is no longer held for this purchase, so it can't be allocated. Nothing was changed.");
        }
    }

    /** FV-2's last check: no confirmed Paystack payment for the agreed amount is on file. */
    public static class PaymentNotConfirmed extends PaymentException {
        public PaymentNotConfirmed() {
            super("No payment confirmed by Paystack for the agreed amount is on file for this purchase. Nothing was changed.");
        }
    }

    /** Payout accounts are set for the whole company: a branch-scoped caller (or a narrowed director) can't. */
    public static class SettlementRequiresCompanyWideScope extends PaymentException {
        public SettlementRequiresCompanyWideScope() {
            super("The payout account is set for the whole company. Switch to the company-wide view to change it.");
        }
    }

    public static class UnknownBank extends PaymentException {
        public UnknownBank() {
            super("That bank isn't one Paystack can pay to. Choose a bank from the list.");
        }
    }

    /** The bank couldn't match the number — the person's mistake to fix, not a gateway failure. */
    public static class AccountNotResolved extends PaymentException {
        public AccountNotResolved() {
            super("The bank couldn't find that account number. Check the number and the bank, then try again.");
        }
    }

    /** One submission waits at a time (decided with the user). */
    public static class SettlementAccountPending extends PaymentException {
        public SettlementAccountPending() {
            super("Another payout account is already waiting for approval. Withdraw it first to submit a different one.");
        }
    }

    /** Submitting the account already approved changes nothing. */
    public static class SettlementAccountUnchanged extends PaymentException {
        public SettlementAccountUnchanged() {
            super("That account is already your approved payout account.");
        }
    }

    /** No such submission, or another company's — deliberately the same answer. */
    public static class SettlementAccountNotFound extends PaymentException {
        public SettlementAccountNotFound() {
            super("Payout account not found.");
        }
    }

    public static class SettlementAccountNotPending extends PaymentException {
        public SettlementAccountNotPending(String status) {
            super("This payout account isn't waiting for a decision (it is " + status + ").");
        }
    }

    public static class UnknownStatusFilter extends PaymentException {
        public UnknownStatusFilter(String status) {
            super("Unknown status '" + status + "'.");
        }
    }

    /** Sending money needs a second factor on the sender's own login (decided with the user). */
    public static class TwoFactorRequired extends PaymentException {
        public TwoFactorRequired() {
            super("Turn on two-factor authentication for your account before sending payouts.");
        }
    }

    /** Not a verified sale (yet, or any more) — nothing is owed for it. */
    public static class SaleNotPayable extends PaymentException {
        public SaleNotPayable() {
            super("There is no verified sale with that id to pay out.");
        }
    }

    /** At most one live payout per sale. */
    public static class PayoutInProgress extends PaymentException {
        public PayoutInProgress(String status) {
            super("A payout for this sale is " + status + ". Wait for it to finish before sending the next part.");
        }
    }

    /** A suspended or offboarded company's payouts are held (decided with the user). */
    public static class CompanyNotActive extends PaymentException {
        public CompanyNotActive() {
            super("This company isn't active, so its payouts are on hold until it is reactivated.");
        }
    }

    public static class NoApprovedPayoutAccount extends PaymentException {
        public NoApprovedPayoutAccount() {
            super("This company has no approved payout account yet.");
        }
    }

    public static class PayoutNotFound extends PaymentException {
        public PayoutNotFound() {
            super("Payout not found.");
        }
    }

    public static class PayoutNotAwaitingOtp extends PaymentException {
        public PayoutNotAwaitingOtp(String status) {
            super("This payout isn't waiting for a code (it is " + status + ").");
        }
    }

    /** Paystack didn't accept the code. Carries Paystack's own message. */
    public static class OtpRejected extends PaymentException {
        public OtpRejected(String paystackMessage) {
            super("Paystack didn't accept that code: " + paystackMessage);
        }
    }

    /**
     * We asked Paystack to send and got no answer. The payout stays
     * {@code sending}; trying again is safe because it reuses the reference.
     */
    public static class PayoutOutcomeUnknown extends PaymentException {
        public PayoutOutcomeUnknown() {
            super("Paystack didn't answer, so we don't know yet whether it accepted the payout. It is kept as "
                    + "'sending'. Send again — it's safe: the same reference is reused and can't be paid twice.");
        }
    }

    /** Every part of this sale has been paid. */
    public static class SaleAlreadyPaid extends PaymentException {
        public SaleAlreadyPaid() {
            super("This sale has already been paid out in full.");
        }
    }

    /** 5a: the buyer withdrew; the developer's verified shares are all paid out. */
    public static class WithdrawnPlanPaid extends PaymentException {
        public WithdrawnPlanPaid() {
            super("The buyer withdrew from this plan, and every share owed to the developer so far has been paid out. "
                    + "A share of a payment their finance hasn't verified is paid once they verify it.");
        }
    }

    /** 5b: the plan was revoked; the developer's verified shares are all paid out. */
    public static class RevokedPlanPaid extends PaymentException {
        public RevokedPlanPaid() {
            super("This plan was revoked, and every share owed to the developer so far has been paid out. A share "
                    + "of a payment their finance hasn't verified is paid once they verify it.");
        }
    }

    /** 3c: an instalment plan still running, with every share owed so far paid; the rest waits in escrow. */
    public static class HeldInEscrow extends PaymentException {
        public HeldInEscrow() {
            super("Everything owed on this purchase so far has been paid out. The rest is held in escrow until "
                    + "the plan completes.");
        }
    }

    /** A company's payouts are company-wide; a branch-scoped view can't list them. */
    public static class CompanyWideScopeRequired extends PaymentException {
        public CompanyWideScopeRequired() {
            super("Payouts are shown for the whole company. Switch to the company-wide view to see them.");
        }
    }

    /** Not a payment that is owed back (or not one at all). */
    public static class RefundNotDue extends PaymentException {
        public RefundNotDue() {
            super("That payment isn't owed back to the buyer.");
        }
    }

    /** One live refund per payment. */
    public static class RefundInProgress extends PaymentException {
        public RefundInProgress(String status) {
            super("This payment already has a refund that is " + status + ". It can't be refunded again.");
        }
    }

    public static class RefundNotFound extends PaymentException {
        public RefundNotFound() {
            super("Refund not found.");
        }
    }

    public static class RefundNotAwaitingAccount extends PaymentException {
        public RefundNotAwaitingAccount(String status) {
            super("This refund doesn't need a bank account (it is " + status + ").");
        }
    }

    public static class RefundAccountMissing extends PaymentException {
        public RefundAccountMissing() {
            super("The buyer hasn't given a bank account for this refund yet.");
        }
    }

    /** We asked Paystack and got no answer, or a refusal that may mean it already has this refund. */
    public static class RefundOutcomeUnknown extends PaymentException {
        public RefundOutcomeUnknown(String detail) {
            super("We don't know yet whether Paystack accepted this refund — it is kept as 'sending'. " + detail
                    + " Try again later: HomeTrue first asks Paystack whether it already has this refund, so it is "
                    + "never sent twice.");
        }
    }

    /** No late payment waiting on this purchase (not this company's, already resolved, or none). */
    public static class LatePaymentNotFound extends PaymentException {
        public LatePaymentNotFound() {
            super("There's no late payment waiting for a decision on that purchase.");
        }
    }

    /** Phase 3a: a deposit that arrived after the purchase was abandoned can only be refunded for now. */
    public static class LateDepositNotSupported extends PaymentException {
        public LateDepositNotSupported() {
            super("This late payment is an instalment plan's deposit, which can't be allocated yet. Refund the buyer "
                    + "instead. Nothing was changed.");
        }
    }

    /** Someone else reserved, bought or withheld the plot meanwhile: refund is the only ending left. */
    public static class PlotNoLongerAvailable extends PaymentException {
        public PlotNoLongerAvailable() {
            super("The plot is no longer available, so it can't be allocated. Refund the buyer instead. Nothing was changed.");
        }
    }

    /**
     * AL: the allocation letter is issued in the same step as the sale, so a
     * sale on an estate whose letter isn't approved can't be allocated yet.
     */
    public static class LetterNotApproved extends PaymentException {
        public LetterNotApproved() {
            super("This estate's allocation letter isn't approved, so the sale can't be allocated yet — every "
                    + "buyer receives their letter at this step. Ask your Executive Director to preview and approve "
                    + "it, then try again. Nothing was changed.");
        }
    }

    /** Separation of duties: nobody decides on a purchase they made themselves. */
    public static class OwnPurchase extends PaymentException {
        public OwnPurchase() {
            super("You can't decide on your own purchase. Another finance officer or director must.");
        }
    }

    /** 5a: only a plan the buyer is on (deposit verified, not paid off) can be withdrawn from. */
    public static class PlanNotWithdrawable extends PaymentException {
        public PlanNotWithdrawable(String status) {
            super("Only a running instalment plan can be withdrawn from (this purchase is " + status + ").");
        }
    }

    /** 5a: the figures moved since the buyer saw them; they confirm again on the new ones. */
    public static class WithdrawalQuoteChanged extends PaymentException {
        public WithdrawalQuoteChanged() {
            super("Your refund figures changed since you looked at them. Check the new figures and confirm again. "
                    + "Nothing was changed.");
        }
    }

    /** 5b: only a running plan can be revoked. */
    public static class PlanNotRevocable extends PaymentException {
        public PlanNotRevocable(String status) {
            super("Only a running instalment plan can be revoked (this purchase is " + status + ").");
        }
    }

    /** 5b: the purchase was opened with no revocation terms, so it can't be revoked. */
    public static class RevocationTermsMissing extends PaymentException {
        public RevocationTermsMissing() {
            super("This purchase was opened before the estate declared when a plan can be revoked, so it can't be "
                    + "revoked.");
        }
    }

    /** 5b: the declared trigger isn't met — nobody is that far behind. */
    public static class RevocationNotDue extends PaymentException {
        public RevocationNotDue(String detail) {
            super(detail);
        }
    }

    /** 5b: one open notice per plan. */
    public static class RevocationAlreadyNotified extends PaymentException {
        public RevocationAlreadyNotified(LocalDate deadline) {
            super("The buyer already has notice of revocation, with until " + deadline + " to pay what is overdue.");
        }
    }

    /** 5b: nothing to cancel or confirm. */
    public static class NoOpenRevocation extends PaymentException {
        public NoOpenRevocation() {
            super("There is no open notice of revocation on this plan.");
        }
    }

    /** 5b: the buyer still has time. */
    public static class RevocationNoticeRunning extends PaymentException {
        public RevocationNoticeRunning(LocalDate deadline) {
            super("The buyer has until the end of " + deadline + " to pay what is overdue. The revocation can be "
                    + "confirmed from the day after.");
        }
    }

    /** 5b: revocation is a company decision, made with the company-wide view. */
    public static class RevocationRequiresCompanyWideScope extends PaymentException {
        public RevocationRequiresCompanyWideScope() {
            super("Revoking a plan is a company-wide decision. Switch to the company-wide view to do it.");
        }
    }
}
