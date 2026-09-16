package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The SA-1.2 verification state machine — whether a tenant can publish to
 * the marketplace or collect payments. Independent of {@link TenantStatus};
 * see that type's Javadoc.
 * <p>
 * Matches the frontend's {@code VerificationState} union in
 * {@code tenantsService.ts} exactly. {@link #value} is the lowercase wire
 * value; see AGENTS.md for the enum JSON-mapping strategy.
 */
public enum VerificationState {

    CREATED("created"),
    DOCUMENTS_SUBMITTED("documents_submitted"),
    UNDER_REVIEW("under_review"),
    VERIFIED("verified"),
    REJECTED("rejected"),
    SUSPENDED("suspended");

    private final String value;

    VerificationState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static VerificationState fromValue(String value) {
        for (VerificationState state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown VerificationState: " + value);
    }
}
