package com.techcomfort.landvaultbackend.tenancy.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Verification status of a single {@link OrganizationDocumentType} upload.
 * Matches the frontend's {@code DocumentStatus} union in
 * {@code tenantsService.ts} exactly. {@link #value} is the lowercase wire
 * value; see AGENTS.md for the enum JSON-mapping strategy.
 */
public enum DocumentStatus {

    PENDING("pending"),
    VERIFIED("verified"),
    REJECTED("rejected");

    private final String value;

    DocumentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DocumentStatus fromValue(String value) {
        for (DocumentStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown DocumentStatus: " + value);
    }
}
