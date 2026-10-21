package com.techcomfort.landvaultbackend.checkout;

import java.time.Instant;
import java.util.UUID;

/** A purchase's offer letter: which one, and whether the buyer has accepted it ({@code acceptedAt} null if not). */
public record OfferSummary(UUID id, String number, Instant issuedAt, Instant acceptedAt) {

    public boolean accepted() {
        return acceptedAt != null;
    }
}
