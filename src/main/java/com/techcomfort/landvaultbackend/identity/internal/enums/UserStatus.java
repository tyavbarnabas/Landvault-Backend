package com.techcomfort.landvaultbackend.identity.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle status of a user account — whether the holder can use the
 * portal at all. Independent of any tenant/company verification state (see
 * {@code tenancy.internal.domain.VerificationState}), and independent of
 * KYC status (owned by the future {@code kyc} module).
 * <p>
 * Persisted with {@code @Enumerated(EnumType.STRING)} — the constant name
 * (e.g. {@code PENDING_VERIFICATION}) is what's stored in the database.
 * {@link #value} is the lowercase wire value used on the JSON boundary, per
 * the enum JSON-mapping strategy recorded in AGENTS.md.
 */
public enum UserStatus {

    PENDING_VERIFICATION("pending_verification"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    DEACTIVATED("deactivated");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static UserStatus fromValue(String value) {
        for (UserStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown UserStatus: " + value);
    }
}
