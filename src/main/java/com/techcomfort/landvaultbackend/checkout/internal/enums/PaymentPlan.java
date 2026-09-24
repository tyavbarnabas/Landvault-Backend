package com.techcomfort.landvaultbackend.checkout.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the buyer intends to pay. Wire values are the frontend's
 * {@code PaymentPlanType} union verbatim.
 * <p>
 * Recorded here and nowhere else in this slice: <strong>generating the
 * schedule belongs to {@code finance}</strong> (TX-2). This column says what
 * was chosen, not what is owed when.
 */
public enum PaymentPlan {

    OUTRIGHT("outright"),

    /** Carries a month count; the schedule itself is finance's to produce. */
    INSTALLMENT("installment"),

    /**
     * Payments tied to construction milestones. Only meaningful for built
     * property, which is not built either — see AGENTS.md on completed units
     * before off-plan.
     */
    MILESTONE("milestone");

    private final String value;

    PaymentPlan(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PaymentPlan fromValue(String value) {
        for (PaymentPlan plan : values()) {
            if (plan.value.equalsIgnoreCase(value)) {
                return plan;
            }
        }
        throw new IllegalArgumentException("Unknown PaymentPlan: " + value);
    }
}
