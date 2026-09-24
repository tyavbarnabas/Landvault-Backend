package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What the <strong>seller is offering</strong> — sale, rent, or either.
 * <p>
 * <strong>Do not confuse this with {@code PlotIntent}</strong>, which
 * describes what a <em>buyer</em> means to do with the land (develop it or
 * hold it as an investment). Different axes, both legitimate: a plot can be
 * {@code FOR_SALE} with {@code PlotIntent.INVESTMENT}, or {@code FOR_RENT}
 * with no buyer intent at all. Folding one into the other would lose a real
 * distinction — they answer different questions asked by different parties.
 * <p>
 * <strong>This is the only column rentals need in {@code inventory}.</strong>
 * A rental is not a different property, it is a different <em>transaction</em>:
 * recurring rent, a tenancy agreement, a deposit, renewals, and ownership
 * never transferring. All of that belongs to {@code sales}, {@code finance}
 * and {@code documents}. Estates, blocks, plots and tiers are the same rows
 * either way — don't build a parallel rental model here. See AGENTS.md.
 */
public enum ListingIntent {

    FOR_SALE("for_sale"),
    FOR_RENT("for_rent"),
    BOTH("both");

    private final String value;

    ListingIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ListingIntent fromValue(String value) {
        for (ListingIntent intent : values()) {
            if (intent.value.equalsIgnoreCase(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("Unknown ListingIntent: " + value);
    }
}
