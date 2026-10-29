package com.techcomfort.landvaultbackend.resale.internal.enums;

import java.util.Locale;

/** A resale listing's state. Later phases add a buyer holding it and the sale completing. */
public enum ListingStatus {
    /** Up, and no buyer holds it. */
    LISTED,
    /** The seller took it down. */
    WITHDRAWN,
    /** 90 days passed with no buyer. */
    EXPIRED,
    /** A rule took it down: arrears, a notice, the estate leaving the marketplace, an early price below the agreed one. */
    ENDED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
