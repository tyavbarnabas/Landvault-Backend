package com.techcomfort.landvaultbackend.conflicts.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Who is involved, which is what decides urgency (CD-3). Wire values match
 * the frontend's {@code ConflictSeverity} union exactly.
 */
public enum ConflictSeverity {

    /**
     * Different tenants. Two companies claim the same ground, one of them is
     * wrong, and a buyer could pay the wrong party. Blocks publication.
     */
    HIGH("high"),
    /**
     * Same tenant. One company's own estates or plots overlap — almost
     * certainly a survey or data-entry error, with nobody about to lose
     * money. Warns, but deliberately does not block publication.
     */
    MEDIUM("medium");

    private final String value;

    ConflictSeverity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ConflictSeverity fromValue(String value) {
        for (ConflictSeverity candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ConflictSeverity: " + value);
    }
}
