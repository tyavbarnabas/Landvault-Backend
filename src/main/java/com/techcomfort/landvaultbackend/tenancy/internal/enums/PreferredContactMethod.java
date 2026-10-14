package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a company asking to be onboarded would like HomeTrue to reach them. */
public enum PreferredContactMethod {

    EMAIL("email"),
    PHONE("phone"),
    WHATSAPP("whatsapp"),
    MEETING("meeting");

    private final String value;

    PreferredContactMethod(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PreferredContactMethod fromValue(String value) {
        for (PreferredContactMethod method : values()) {
            if (method.value.equalsIgnoreCase(value)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown contact method: " + value
                + " (expected email, phone, whatsapp or meeting)");
    }
}
