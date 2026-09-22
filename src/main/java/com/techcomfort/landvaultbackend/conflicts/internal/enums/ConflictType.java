package com.techcomfort.landvaultbackend.conflicts.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What kind of geometry overlapped. Wire values are lowercase to match the
 * rest of the API; neither exists in the frontend's own union, which models
 * estate conflicts only — see {@code ListingConflictDto}.
 */
public enum ConflictType {

    /** Two estates, potentially belonging to different companies. */
    ESTATE_OVERLAP("estate_overlap"),
    /**
     * Two plots inside one estate — AGENTS.md records this as the
     * <em>more common</em> version of the scam, and it was undetectable
     * until plots carried geometry.
     */
    PLOT_OVERLAP("plot_overlap");

    private final String value;

    ConflictType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ConflictType fromValue(String value) {
        for (ConflictType candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ConflictType: " + value);
    }
}
