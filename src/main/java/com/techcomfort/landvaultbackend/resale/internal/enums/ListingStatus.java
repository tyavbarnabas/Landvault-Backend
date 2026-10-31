package com.techcomfort.landvaultbackend.resale.internal.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** A resale listing's state. Later phases add a buyer holding it and the sale completing. */
public enum ListingStatus {
    /** The estate's terms need the developer's consent: off the marketplace until they give it. */
    AWAITING_CONSENT,
    /** Up, and no buyer holds it. */
    LISTED,
    /** The seller took it down. */
    WITHDRAWN,
    /** 90 days passed with no buyer. */
    EXPIRED,
    /**
     * A rule took it down: arrears, a notice, the estate leaving the marketplace, an early price below the
     * agreed one, or no answer to a consent request in time.
     */
    ENDED,
    /** The developer refused consent, with a reason the seller reads. */
    REFUSED;

    public static final Set<ListingStatus> OPEN = EnumSet.of(AWAITING_CONSENT, LISTED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public static ListingStatus fromWire(String value) {
        for (ListingStatus s : values()) {
            if (s.wire().equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown status '" + value + "'.");
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
