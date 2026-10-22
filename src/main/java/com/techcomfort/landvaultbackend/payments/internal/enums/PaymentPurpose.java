package com.techcomfort.landvaultbackend.payments.internal.enums;

/** What a payment was for (changesets 101, 102). */
public enum PaymentPurpose {
    /** The full agreed price. */
    OUTRIGHT,
    /** An instalment plan's deposit, from the purchase's fixed schedule. */
    DEPOSIT,
    /** One instalment of the schedule; the payment names which ({@code instalmentSequence}). */
    INSTALMENT,
    /** Everything still owed on the plan, in one payment ("pay off the balance"). */
    BALANCE
}
