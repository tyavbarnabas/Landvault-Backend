package com.techcomfort.landvaultbackend.kyc.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a buyer's verification stands. Wire values are the frontend's
 * {@code KycStatus} union verbatim.
 * <p>
 * The task spec named these {@code NOT_STARTED} and {@code VERIFIED}; the
 * frontend's contract, which already exists, says {@code unsubmitted} and
 * {@code approved}. The contract wins — see AGENTS.md.
 */
public enum KycStatus {

    /**
     * Also what a buyer with <em>no record at all</em> reports. An
     * unverified buyer has no row rather than a defaulted one, so this
     * constant is never actually persisted by the submit path — it is the
     * honest reading of an absence.
     */
    UNSUBMITTED("unsubmitted"),
    SUBMITTED("submitted"),
    UNDER_REVIEW("under_review"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    KycStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static KycStatus fromValue(String value) {
        for (KycStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown KycStatus: " + value);
    }
}
