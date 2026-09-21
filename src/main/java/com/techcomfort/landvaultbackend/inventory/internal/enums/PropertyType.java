package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a plot is bare land or carries a building.
 * <p>
 * The hierarchy <strong>extends rather than forks</strong>:
 * {@code Estate → Block → Plot → Unit (0..n)}. Land is always the base —
 * bare land is a plot with no units, a house is a plot with one, an apartment
 * block is a plot with many. {@link #BUILT} therefore means "this plot has
 * units", not "this is a different kind of thing".
 * <p>
 * <strong>Geometry stays at plot level regardless.</strong> Two flats in the
 * same building do not overlap spatially, so conflict detection must never
 * try to reason about them — a double-sold apartment is caught by unit
 * identity, not by {@code ST_Intersects}. See AGENTS.md.
 * <p>
 * No {@code units} table exists yet; this column makes one possible without
 * committing to its shape.
 */
public enum PropertyType {

    LAND("land"),
    BUILT("built");

    private final String value;

    PropertyType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PropertyType fromValue(String value) {
        for (PropertyType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown PropertyType: " + value);
    }
}
