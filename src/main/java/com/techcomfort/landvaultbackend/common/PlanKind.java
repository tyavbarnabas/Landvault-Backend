package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What kind of plan a developer declares (see AGENTS.md, "Instalment plans"
 * and "Flexible contribution"). The wire value matches a purchase's
 * {@code plan}: {@code installment} or {@code flexible}.
 */
public enum PlanKind {

    /** A deposit, then a fixed schedule of instalments. */
    INSTALMENT("installment"),
    /** A minimum to start, then any amount at any time until finished; no schedule. */
    FLEXIBLE("flexible");

    private final String value;

    PlanKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlanKind fromValue(String value) {
        for (PlanKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown PlanKind: " + value);
    }
}
