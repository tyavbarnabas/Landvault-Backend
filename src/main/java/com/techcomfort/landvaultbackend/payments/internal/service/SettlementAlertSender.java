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
    record RefundNeedsAccountAlert(String to, String amount, String paymentReference) {
    }

    /** A refund failed or needs a person — sent to LandVault's Super Admins. */
    record RefundProblemAlert(String to, String paymentReference, String amount, String status, String detail) {
    }

    /** Late money resolved: the buyer is told either way (decided with the user). */
    record LatePaymentAlert(String to, String plotLabel, String estateName, String amount, boolean allocated,
                            String reason) {
    }

    void accountSubmitted(AccountSubmittedAlert alert);

    void latePaymentResolved(LatePaymentAlert alert);

    void refundNeedsAccount(RefundNeedsAccountAlert alert);

    void refundProblem(RefundProblemAlert alert);

    void payoutProblem(PayoutProblemAlert alert);
}
