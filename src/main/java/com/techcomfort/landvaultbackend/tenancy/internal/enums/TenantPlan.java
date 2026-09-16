package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Billing plan, gating per-feature entitlements (SA-1.3). Matches the
 * frontend's {@code TenantPlan} union in {@code tenantsService.ts} exactly.
 * {@link #value} is the lowercase wire value; see AGENTS.md for the enum
 * JSON-mapping strategy.
 */
public enum TenantPlan {

    STARTER("starter"),
    GROWTH("growth"),
    ENTERPRISE("enterprise");

    private final String value;

    TenantPlan(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TenantPlan fromValue(String value) {
        for (TenantPlan plan : values()) {
            if (plan.value.equalsIgnoreCase(value)) {
                return plan;
            }
        }
        throw new IllegalArgumentException("Unknown TenantPlan: " + value);
    }
}
