package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a refund deduction is calculated against.
 * <p>
 * <strong>Both source letters are ambiguous about this, and it changes the
 * figure materially</strong> — 20% of a ₦500,000 deposit is ₦100,000, while
 * 20% of a ₦4,500,000 headline price is ₦900,000, which is more than the
 * buyer has paid. The platform does not guess; the developer states it.
 */
public enum RefundAppliesTo {

    /**
     * Deducted from what the buyer has actually paid so far. Computing this
     * for a real buyer needs payment records, which do not exist yet — see
     * RF-3, which belongs to {@code finance}.
     */
    AMOUNT_PAID("amount_paid"),

    /** Deducted from the full price, regardless of how much has been paid. */
    TOTAL_PRICE("total_price");

    private final String value;

    RefundAppliesTo(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RefundAppliesTo fromValue(String value) {
        for (RefundAppliesTo appliesTo : values()) {
            if (appliesTo.value.equalsIgnoreCase(value)) {
                return appliesTo;
            }
        }
        throw new IllegalArgumentException("Unknown RefundAppliesTo: " + value);
    }
}
