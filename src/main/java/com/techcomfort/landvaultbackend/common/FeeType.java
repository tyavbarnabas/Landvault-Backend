package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A kind of charge a buyer commits to beyond the land price.
 * <p>
 * Every constant here appears in at least one of the four real Nigerian
 * allocation letters this feature was derived from. None was invented to
 * look thorough — a fee type nobody charges is a field nobody fills in, and
 * an empty field on a disclosure screen reads as "nothing to declare" when
 * it means "nobody asked".
 * <p>
 * In {@code common} because both {@code inventory} (which owns the
 * declaration) and {@code marketplace} (which publishes it) need the wire
 * values.
 */
public enum FeeType {

    /** Double King ₦10,000, Top Rank ₦10,000 — and non-refundable in both. */
    APPLICATION("application"),

    /** Surveying and pegging the plot's corners. Double King ₦300,000. */
    SETTING_OUT("setting_out"),

    /**
     * Roads, drainage, power. The largest charge in both letters, and the
     * one that makes the advertised price misleading: Top Rank's is
     * ₦7,000,000 against ₦4,500,000 of land — 156% of what was advertised.
     */
    INFRASTRUCTURE("infrastructure"),

    /** Oversight of the buyer's own build. Double King ₦200,000. */
    CONSTRUCTION_SUPERVISION("construction_supervision"),

    /** Recurring. Double King's falls due 15 January each year. */
    FACILITY_MANAGEMENT("facility_management"),

    SURVEY("survey"),

    LEGAL("legal"),

    /** Requires a label — an unnamed charge is a number, not a disclosure. */
    OTHER("other");

    private final String value;

    FeeType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static FeeType fromValue(String value) {
        for (FeeType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown FeeType: " + value);
    }
}
