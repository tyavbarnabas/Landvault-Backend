package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a check's result came from — and the reason
 * {@code EstateVerificationCheck} is worth more than a boolean.
 * <p>
 * A green badge produced by a real registry API call and a green badge
 * produced by a human eyeballing a PDF are not the same claim, and a buyer
 * deciding whether to part with money deserves to know which one they are
 * looking at. If this collapsed into "verified: true", the badge would mean
 * nothing.
 * <p>
 * An enum rather than free text specifically so {@link #MANUAL_REVIEW} can
 * never be spelled two ways — a typo would silently split the one distinction
 * this column exists to make. Adding an integration is a one-line changeset.
 */
public enum VerificationSource {

    /** A person read a document and formed a judgement. Not an integration. */
    MANUAL_REVIEW("manual_review"),
    AGIS("agis"),
    QOREID("qoreid"),
    CAC_REGISTRY("cac_registry");

    private final String value;

    VerificationSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static VerificationSource fromValue(String value) {
        for (VerificationSource source : values()) {
            if (source.value.equalsIgnoreCase(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown VerificationSource: " + value);
    }
}
