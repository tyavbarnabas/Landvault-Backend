package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Nigerian company registration type, collected at onboarding (Stage 1).
 * Matches the frontend's {@code CompanyType} union in
 * {@code tenantsService.ts} exactly.
 * <p>
 * Wire values contain spaces, parentheses and a slash, so they can't be a
 * Java enum constant name — {@link #value} carries the literal string and
 * {@code @JsonValue}/{@code @JsonCreator} need it "regardless" of whatever
 * mapping convention plainer enums use (see AGENTS.md).
 */
public enum CompanyType {

    LIMITED_LIABILITY("Limited Liability (Ltd)"),
    PLC("PLC"),
    BUSINESS_NAME_ENTERPRISE("Business Name/Enterprise"),
    INCORPORATED_TRUSTEES("Incorporated Trustees");

    private final String value;

    CompanyType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CompanyType fromValue(String value) {
        for (CompanyType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown CompanyType: " + value);
    }
}
