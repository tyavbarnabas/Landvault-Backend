package com.techcomfort.landvaultbackend.conflicts.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a conflict sits in review (CD-7). The first four match the
 * frontend's {@code ConflictStatus} union exactly; {@code AUTO_RESOLVED} is
 * a backend-only addition the frontend does not yet know about — see
 * AGENTS.md.
 */
public enum ConflictStatus {

    /** Detected, nobody has looked yet. */
    OPEN("open"),
    /** A Super Admin is working it. Survives a re-scan (CD-8). */
    INVESTIGATING("investigating"),
    /** Judged a real duplicate. Terminal for review, but still blocks publication. */
    CONFIRMED_DUPLICATE("confirmed_duplicate"),
    /** Judged a false positive. Terminal, and stops blocking publication. */
    DISMISSED("dismissed"),
    /**
     * The geometry was corrected and the overlap no longer exists (CD-9).
     * <strong>Set by detection only, never by a reviewer</strong> — a
     * request to transition here manually is rejected.
     */
    AUTO_RESOLVED("auto_resolved");

    private final String value;

    ConflictStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ConflictStatus fromValue(String value) {
        for (ConflictStatus candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ConflictStatus: " + value);
    }

    /**
     * Whether this status still counts as live — matching the partial
     * unique index in changeset 046 exactly. A confirmed duplicate is live:
     * it is a standing consequence, not a closed case.
     */
    public boolean isLive() {
        return this == OPEN || this == INVESTIGATING || this == CONFIRMED_DUPLICATE;
    }

    /** Whether a reviewer may move a conflict into this status. */
    public boolean isManuallyReachable() {
        return this != AUTO_RESOLVED && this != OPEN;
    }
}
