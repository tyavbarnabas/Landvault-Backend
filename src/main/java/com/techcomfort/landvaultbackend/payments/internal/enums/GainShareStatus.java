package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;

/** A flexible buyer's claim on a later sale's gain (changeset 114). */
public enum GainShareStatus {
    /** Waiting for the plot's next completed sale through HomeTrue. */
    OPEN,
    /** That sale was above the buyer's agreed price: their share is owed, paid as a payout tranche. */
    OWED,
    /** That sale was not above it (or the developer's payout from it was already spoken for): nothing is owed. */
    NO_GAIN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
