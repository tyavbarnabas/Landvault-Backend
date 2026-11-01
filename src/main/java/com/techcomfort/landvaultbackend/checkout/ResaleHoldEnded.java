package com.techcomfort.landvaultbackend.checkout;

import java.time.Instant;
import java.util.UUID;

/**
 * Sell my plot: a hold on a resale listing ended — released by the buyer,
 * expired, abandoned or rejected ({@code released} / {@code expired}), or
 * converted when the sale goes through. Published inside the transaction that
 * ended it and consumed by the resale module with a plain synchronous
 * {@code @EventListener}, so the listing and the hold always end together
 * (the {@code PlotAllocated} pattern).
 *
 * @param outcome {@code released}, {@code expired} or {@code converted}
 */
public record ResaleHoldEnded(UUID reservationId, UUID listingId, String outcome, Instant endedAt) {
}
