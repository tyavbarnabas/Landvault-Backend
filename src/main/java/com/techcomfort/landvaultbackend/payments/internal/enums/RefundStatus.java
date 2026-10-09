package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;

/** One refund's state. Persisted by name; the wire value is lowercase with dashes, like Paystack's. */
public enum RefundStatus {
    /** Saved, Paystack asked, answer not yet known. */
    SENDING,
    PENDING,
    PROCESSING,
    /** Paystack can't return it on its own (typically a bank-transfer payment): the buyer must give an account. */
    NEEDS_ATTENTION,
    /** The money is back with the buyer. */
    PROCESSED,
    /** Paystack couldn't refund it. The only state from which the payment may be refunded again. */
    FAILED;

    /** Unrecognised is PENDING — "not finished", never a failure that would allow a second refund. */
    public static RefundStatus fromPaystack(String status) {
        if (status == null) {
            return PENDING;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "processing" -> PROCESSING;
            case "needs-attention" -> NEEDS_ATTENTION;
            case "processed" -> PROCESSED;
            case "failed" -> FAILED;
            default -> PENDING;
        };
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
