package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a tenant's staff can use the portal at all — deliberately
 * independent of {@link VerificationState} (whether the tenant can publish
 * to the marketplace or take payments). A tenant can be {@code ACTIVE} and
 * {@code UNDER_REVIEW} (verification-wise) at once; that's intentional, not
 * a bug — forcing full verification before any portal access would kill
 * onboarding completion.
 * <p>
 * Matches the frontend's {@code TenantStatus} union in
 * {@code tenantsService.ts} exactly — values must not be invented or
 * renamed. {@link #value} is the lowercase wire value; see AGENTS.md for
 * the enum JSON-mapping strategy.
 */
public enum TenantStatus {

    ACTIVE("active"),
    SUSPENDED("suspended"),
    OFFBOARDED("offboarded");

    private final String value;

    TenantStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TenantStatus fromValue(String value) {
        for (TenantStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown TenantStatus: " + value);
    }
}
