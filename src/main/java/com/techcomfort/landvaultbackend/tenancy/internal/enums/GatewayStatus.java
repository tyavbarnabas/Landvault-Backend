package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Connection status of a single {@link GatewayName} for a tenant. Matches
 * the frontend's {@code GatewayStatus} union in {@code tenantsService.ts}
 * exactly. {@link #value} is the lowercase wire value; see AGENTS.md for the
 * enum JSON-mapping strategy.
 */
public enum GatewayStatus {

    CONNECTED("connected"),
    PENDING("pending");

    private final String value;

    GatewayStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static GatewayStatus fromValue(String value) {
        for (GatewayStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown GatewayStatus: " + value);
    }
}
