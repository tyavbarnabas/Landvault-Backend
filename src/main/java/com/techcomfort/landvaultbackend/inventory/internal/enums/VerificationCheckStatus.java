package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A check's honest state, matching the frontend's {@code VerificationCheck.status}
 * union.
 * <p>
 * <strong>{@link #NOT_CHECKED} is the default and must never render as
 * positive.</strong> The absence of a check is not a clean bill of health —
 * the frontend removed hardcoded "No encroachment notices on file" claims for
 * exactly this reason. Anything displaying these values has to distinguish
 * "we checked and it was fine" from "nobody has looked".
 */
public enum VerificationCheckStatus {

    VERIFIED("verified"),
    PENDING("pending"),
    NOT_CHECKED("not_checked"),
    FAILED("failed");

    private final String value;

    VerificationCheckStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static VerificationCheckStatus fromValue(String value) {
        for (VerificationCheckStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown VerificationCheckStatus: " + value);
    }
}
