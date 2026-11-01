package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sell my plot, phase 5a: a buyer's hold on a resale plot, as that buyer sees
 * it. Never the seller, never what they paid or how the price is split.
 */
@Schema(name = "ResaleHold")
public record ResaleHoldDto(
        @Schema(description = "The reservation behind the hold: Proceed to buy names it, and "
                + "`DELETE /api/reservations/{id}` lets it go.") UUID reservationId,
        UUID listingId,
        UUID estateId,
        UUID plotId,
        @Schema(description = "Fixed now, for this hold: what you will pay.") BigDecimal price,
        String currency,
        Instant expiresAt,
        @Schema(description = "Seconds left, floored at 0. The hold can't be extended.") long secondsRemaining
) {
}
