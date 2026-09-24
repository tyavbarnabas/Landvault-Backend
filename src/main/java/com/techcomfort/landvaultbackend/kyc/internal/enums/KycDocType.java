package com.techcomfort.landvaultbackend.kyc.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The documents this platform asks a buyer for. Wire values match the
 * frontend's {@code KycDocType} union.
 * <p>
 * Deliberately short: a local buyer submits a NIN and nothing else. Asking
 * a Nigerian buyer for a utility bill on top is a friction the frontend
 * explicitly rejected, and collecting a document nobody checks is the same
 * liability-without-benefit that got {@code directors.bvn} removed.
 */
public enum KycDocType {

    NIN("nin"),
    PASSPORT("passport"),
    PROOF_OF_ADDRESS("proof_of_address");

    private final String value;

    KycDocType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static KycDocType fromValue(String value) {
        for (KycDocType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown KycDocType: " + value);
    }
}
