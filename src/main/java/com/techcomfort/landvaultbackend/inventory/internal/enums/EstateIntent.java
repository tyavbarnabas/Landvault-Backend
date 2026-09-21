package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What an estate is sold for. Deliberately a different set from
 * {@link PlotIntent}: an estate may offer {@code BOTH}, but an individual
 * plot is sold as one or the other, never both at once.
 */
public enum EstateIntent {

    DEVELOPMENT("development"),
    INVESTMENT("investment"),
    BOTH("both");

    private final String value;

    EstateIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EstateIntent fromValue(String value) {
        for (EstateIntent intent : values()) {
            if (intent.value.equalsIgnoreCase(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("Unknown EstateIntent: " + value);
    }
}
