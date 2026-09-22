package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The land title instrument an estate holds, matching the frontend's
 * {@code Estate.titleType} union exactly.
 * <p>
 * These wire values contain spaces and an apostrophe ({@code "Governor's
 * Consent"}), which is precisely why this codebase uses an explicit
 * {@link JsonValue}/{@link JsonCreator} pair rather than a global Jackson
 * naming strategy — no case conversion produces them. See AGENTS.md.
 */
public enum TitleType {

    C_OF_O("C of O"),
    R_OF_O("R of O"),
    GOVERNORS_CONSENT("Governor's Consent"),
    GAZETTE("Gazette");

    private final String value;

    TitleType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TitleType fromValue(String value) {
        for (TitleType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown TitleType: " + value);
    }
}
