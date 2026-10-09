package com.techcomfort.landvaultbackend.payments.internal.exceptions;

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

    /** A company's payouts are company-wide; a branch-scoped view can't list them. */
    public static class CompanyWideScopeRequired extends PaymentException {
        public CompanyWideScopeRequired() {
            super("Payouts are shown for the whole company. Switch to the company-wide view to see them.");
        }
    }
}
