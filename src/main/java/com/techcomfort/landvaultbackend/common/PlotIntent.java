package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What an individual plot is sold as. No {@code BOTH} — see
 * {@link EstateIntent}, which has one because an estate can offer both.
 * <p>
 * <strong>Not the same axis as {@code ListingIntent}.</strong> This is what
 * the <em>buyer</em> means to do with the land — build on it, or hold it.
 * {@code ListingIntent} is what the <em>seller</em> is offering — sale or
 * rent. A plot can legitimately be {@code FOR_SALE} and {@code INVESTMENT} at
 * once. Don't fold these together while tidying up; they answer different
 * questions asked by different parties.
 */
public enum PlotIntent {

    DEVELOPMENT("development"),
    INVESTMENT("investment");

    private final String value;

    PlotIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlotIntent fromValue(String value) {
        for (PlotIntent intent : values()) {
            if (intent.value.equalsIgnoreCase(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("Unknown PlotIntent: " + value);
    }
}
