package com.techcomfort.landvaultbackend.checkout;

import java.time.Instant;
import java.util.UUID;

/** Sell my plot, phase 5a: the reservation behind a new buyer's hold on a resale listing, and when it ends. */
public record ResaleHoldOpened(UUID reservationId, Instant expiresAt) {
}
