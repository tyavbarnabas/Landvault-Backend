package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How often a plan's instalments fall due. */
public enum PaymentFrequency {

    MONTHLY("monthly", 1),
    QUARTERLY("quarterly", 3);

    private final String value;
    private final int months;

    PaymentFrequency(String value, int months) {
        this.value = value;
        this.months = months;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Months between instalments. */
    public int months() {
        return months;
    }

    @JsonCreator
    public static PaymentFrequency fromValue(String value) {
        for (PaymentFrequency frequency : values()) {
            if (frequency.value.equalsIgnoreCase(value)) {
                return frequency;
            }
        }
        throw new IllegalArgumentException("Unknown PaymentFrequency: " + value);
    }
}
