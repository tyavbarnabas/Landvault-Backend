package com.techcomfort.landvaultbackend.tenancy.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A payment gateway a tenant can connect for settlement. Matches the
 * frontend's {@code GatewayName} union in {@code tenantsService.ts} exactly.
 * <p>
 * Wire values are the gateway's own brand capitalization ({@code "Paystack"},
 * not {@code "paystack"} or {@code "PAYSTACK"}), so {@link #value} carries
 * the literal string via {@code @JsonValue}/{@code @JsonCreator} rather
 * than a lowercase-by-convention naming strategy — see AGENTS.md.
 */
public enum GatewayName {

    PAYSTACK("Paystack"),
    FLUTTERWAVE("Flutterwave"),
    MONNIFY("Monnify"),
    OPAY("Opay"),
    TITAN("Titan");

    private final String value;

    GatewayName(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static GatewayName fromValue(String value) {
        for (GatewayName name : values()) {
            if (name.value.equalsIgnoreCase(value)) {
                return name;
            }
        }
        throw new IllegalArgumentException("Unknown GatewayName: " + value);
    }
}
