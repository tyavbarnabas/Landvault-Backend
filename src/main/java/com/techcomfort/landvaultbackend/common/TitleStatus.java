package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether anyone but the developer has checked an estate's title. A developer
 * declares; only HomeTrue moves it to {@link #VERIFIED} or {@link #REJECTED}.
 * Shared because the marketplace publishes it beside the title type: a buyer
 * never sees a title type without this. See AGENTS.md, "Estate titles".
 */
public enum TitleStatus {

    /** Declared by the developer; nobody else has looked yet. */
    PENDING("pending"),
    VERIFIED("verified"),
    REJECTED("rejected");

    private final String value;

    TitleStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TitleStatus fromValue(String value) {
        for (TitleStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown title status: " + value);
    }
}
