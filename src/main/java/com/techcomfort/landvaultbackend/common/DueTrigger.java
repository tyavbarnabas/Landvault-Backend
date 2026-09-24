package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * When a charge falls due (FD-3). Timing is part of the disclosure: a buyer
 * who can afford a plot cannot necessarily afford it in the order the
 * charges arrive.
 */
public enum DueTrigger {

    AT_APPLICATION("at_application"),

    AT_ALLOCATION("at_allocation"),

    /**
     * Nominally conditional — and in both source letters, not really. Each
     * one <em>requires</em> the buyer to build (Double King clause 1
     * mandates a 4-bedroom terrace duplex, Top Rank clause 2 a 2-bedroom),
     * so a charge triggered by building is unavoidable. See
     * {@code estate_fees.is_mandatory}.
     */
    ON_CONSTRUCTION_START("on_construction_start"),

    ON_MILESTONE("on_milestone"),

    /**
     * Recurring, for as long as the buyer owns the plot.
     * <p>
     * <strong>Deliberately excluded from total commitment.</strong> A
     * purchase total is a one-off figure; folding one year of a perpetual
     * charge into it would be arbitrary (why one year?) and would misstate
     * both the purchase and the obligation. Recurring charges are reported
     * separately, which is also how the source letters present them — and
     * how their own stated totals are arrived at.
     */
    ANNUAL("annual"),

    BEFORE_OCCUPATION("before_occupation");

    private final String value;

    DueTrigger(String value) {
        this.value = value;
    }

    /** Whether this charge recurs rather than falling due once. */
    public boolean isRecurring() {
        return this == ANNUAL;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DueTrigger fromValue(String value) {
        for (DueTrigger trigger : values()) {
            if (trigger.value.equalsIgnoreCase(value)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("Unknown DueTrigger: " + value);
    }
}
