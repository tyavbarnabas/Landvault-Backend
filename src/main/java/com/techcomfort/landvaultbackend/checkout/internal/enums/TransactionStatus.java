package com.techcomfort.landvaultbackend.checkout.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a purchase stands. Wire values are the frontend's
 * {@code TransactionStatus} union verbatim.
 * <p>
 * Written here: {@link #PENDING_PAYMENT} at creation; {@link #AWAITING_FINANCE}
 * when the payments module confirms the money with Paystack; {@link #ABANDONED}
 * when nothing was paid after the hold ran out. A payment signal is never
 * treated as allocation — that follows a finance-role human (TX-3, AGENTS.md's
 * two-step payment rule).
 * <p>
 * The full union is declared here anyway, rather than narrowed to the one
 * value produced today. Unlike {@code otp_codes.purpose} — a backend-invented
 * discriminator whose unused values were deliberately forbidden by a CHECK —
 * these five are an existing frontend contract this backend has to be able to
 * deserialize and document. The constraint that matters is enforced in code:
 * nothing here can advance a transaction past pending.
 */
public enum TransactionStatus {

    /** Created at reservation. The buyer sees payment pending, never complete. */
    PENDING_PAYMENT("pending_payment"),

    /** finance: a gateway or transfer signal arrived. Not confirmation. */
    PAYMENT_RECEIVED("payment_received"),

    /** finance: queued for a human to verify. */
    AWAITING_FINANCE("awaiting_finance"),

    /** finance: a finance-role human confirmed it. Only now may a plot be allocated. */
    VERIFIED("verified"),

    /** finance: the payment did not check out. */
    REJECTED("rejected"),
    /**
     * Nothing was paid after the hold ran out (plus a grace period); the plot
     * went back on sale. Backend-added — the frontend's union needs it too.
     */
    ABANDONED("abandoned");

    private final String value;

    TransactionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TransactionStatus fromValue(String value) {
        for (TransactionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown TransactionStatus: " + value);
    }
}
