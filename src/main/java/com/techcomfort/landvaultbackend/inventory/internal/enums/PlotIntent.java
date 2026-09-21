package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What an individual plot is sold as. No {@code BOTH} — see
 * {@link EstateIntent}, which has one because an estate can offer both.
 */
public enum PlotIntent {

    DEVELOPMENT("development"),
    INVESTMENT("investment");

    private final String value;

    PlotIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlotIntent fromValue(String value) {
        for (PlotIntent intent : values()) {
            if (intent.value.equalsIgnoreCase(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("Unknown PlotIntent: " + value);
    }
}
