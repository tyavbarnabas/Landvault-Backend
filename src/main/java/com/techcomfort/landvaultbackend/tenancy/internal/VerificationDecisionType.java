package com.techcomfort.landvaultbackend.tenancy.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A reviewer's decision on a tenant's verification submission, appended to
 * the tenant's append-only {@code verificationHistory} — never overwritten,
 * regardless of outcome. Matches the frontend's {@code VerificationDecision.decision}
 * union in {@code tenantsService.ts} exactly. {@link #value} is the
 * lowercase wire value; see AGENTS.md for the enum JSON-mapping strategy.
 */
public enum VerificationDecisionType {

    APPROVED("approved"),
    REJECTED("rejected"),
    REQUEST_MORE_INFO("request_more_info");

    private final String value;

    VerificationDecisionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static VerificationDecisionType fromValue(String value) {
        for (VerificationDecisionType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown VerificationDecisionType: " + value);
    }
}
