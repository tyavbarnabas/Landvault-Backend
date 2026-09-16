package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Government ID type used to identify a tenant's primary contact or a
 * director. Matches the frontend's {@code GovIdType} union in
 * {@code tenantsService.ts} exactly.
 * <p>
 * {@code "International Passport"} contains a space, so {@link #value}
 * carries the literal wire string via {@code @JsonValue}/{@code @JsonCreator}
 * rather than relying on a case-conversion naming strategy — see AGENTS.md.
 */
public enum GovIdType {

    NIN("NIN"),
    INTERNATIONAL_PASSPORT("International Passport");

    private final String value;

    GovIdType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static GovIdType fromValue(String value) {
        for (GovIdType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown GovIdType: " + value);
    }
}
