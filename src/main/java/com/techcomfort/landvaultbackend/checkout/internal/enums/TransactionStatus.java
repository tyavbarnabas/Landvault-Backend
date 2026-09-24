package com.techcomfort.landvaultbackend.checkout.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a purchase stands. Wire values are the frontend's
 * {@code TransactionStatus} union verbatim.
 * <p>
 * <strong>This module only ever writes {@link #PENDING_PAYMENT}.</strong>
 * Every other constant belongs to {@code finance}, which does not exist yet:
 * a payment signal is never treated as confirmation, and allocation happens
 * only after a finance-role human verifies it (TX-3, and AGENTS.md's
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
    REJECTED("rejected");

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
