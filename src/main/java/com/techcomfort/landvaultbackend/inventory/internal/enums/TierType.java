package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What discriminates a price tier: a land size band, or a built-unit product
 * type.
 * <p>
 * A {@link #LAND_SIZE} tier is a size band — "250 sqm at ₦4.2M". A
 * {@link #UNIT_TYPE} tier is a product — "3-bedroom terrace at ₦85M". Same
 * concept (a priced category a plot belongs to), different discriminator, so
 * {@code size_sqm} is meaningless for the second and {@code label} carries
 * the meaning instead.
 * <p>
 * {@code price} remains the developer's own figure per tier either way,
 * <strong>never derived from a per-sqm rate</strong> — a rule that holds more
 * strongly for {@code UNIT_TYPE}, where a per-sqm rate isn't even definable.
 */
public enum TierType {

    LAND_SIZE("land_size"),
    UNIT_TYPE("unit_type");

    private final String value;

    TierType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TierType fromValue(String value) {
        for (TierType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown TierType: " + value);
    }
}
