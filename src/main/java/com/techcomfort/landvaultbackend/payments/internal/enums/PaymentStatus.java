package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;

/** One payment attempt's state. Persisted by name; the wire value is lowercase. */
public enum PaymentStatus {
    /** The Paystack link exists; nothing has been paid yet. */
    INITIALIZED,
    /** Paystack confirmed the money, for the amount we asked. */
    SUCCEEDED,
    FAILED,
    ABANDONED,
    /** Paystack confirmed a payment for a different amount or currency than we asked — never treated as success. */
    MISMATCHED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
