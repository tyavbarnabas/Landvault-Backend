package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;
import java.util.Set;

/** One payout attempt's state. Persisted by name; the wire value is lowercase. */
public enum PayoutStatus {
    /** Saved, Paystack asked, answer not yet known — never retried as a new attempt, only resent with its reference. */
    SENDING,
    /** Paystack wants the OTP it sent the account owner. No money moves until it is entered. */
    AWAITING_OTP,
    /** Paystack accepted it; the final outcome arrives later (step 8c). */
    PENDING,
    SUCCESS,
    FAILED,
    /** Sent, then returned by the receiving bank. */
    REVERSED,
    /** Stopped before the OTP was entered; nothing was sent. */
    CANCELLED;

    /** The statuses that hold a sale: at most one per sale, enforced by the database. */
    public static final Set<PayoutStatus> LIVE = Set.of(SENDING, AWAITING_OTP, PENDING, SUCCESS);

    public boolean isLive() {
        return LIVE.contains(this);
    }

    /**
     * Paystack's transfer status in our terms. Anything we don't recognise is
     * PENDING — "not finished", never a failure that would free the sale for
     * a second payment.
     */
    public static PayoutStatus fromPaystack(String status) {
        if (status == null) {
            return PENDING;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "otp" -> AWAITING_OTP;
            case "success" -> SUCCESS;
            case "failed" -> FAILED;
            case "reversed" -> REVERSED;
            default -> PENDING;
        };
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
