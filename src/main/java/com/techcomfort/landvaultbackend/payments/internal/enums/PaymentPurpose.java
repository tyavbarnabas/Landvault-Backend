package com.techcomfort.landvaultbackend.payments.internal.enums;

/** What a payment was for (changeset 101). Instalments arrive with phase 3b. */
public enum PaymentPurpose {
    /** The full agreed price. */
    OUTRIGHT,
    /** An instalment plan's deposit, from the purchase's fixed schedule. */
    DEPOSIT
}
