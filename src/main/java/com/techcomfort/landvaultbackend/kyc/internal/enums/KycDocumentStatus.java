package com.techcomfort.landvaultbackend.kyc.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Per-document state, so a rejection can name <em>which</em> document failed
 * and only that one gets resubmitted (KY-3). A single pass/fail flag on the
 * record would make every rejection restart the whole submission.
 */
public enum KycDocumentStatus {

    /** Required of this buyer, not yet provided. */
    MISSING("missing"),
    SUBMITTED("submitted"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    KycDocumentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static KycDocumentStatus fromValue(String value) {
        for (KycDocumentStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown KycDocumentStatus: " + value);
    }
}
