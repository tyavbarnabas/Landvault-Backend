package com.techcomfort.landvaultbackend.resale.internal.enums;

import java.util.Locale;

/** What the seller is selling: a plot paid in full, or one on an instalment or flexible plan. */
public enum ListingKind {
    PAID_IN_FULL,
    INSTALLMENT,
    FLEXIBLE;

    public static ListingKind fromWire(String wire) {
        return valueOf(wire.toUpperCase(Locale.ROOT));
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
