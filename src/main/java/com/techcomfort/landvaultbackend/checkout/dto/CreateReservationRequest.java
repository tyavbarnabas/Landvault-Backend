package com.techcomfort.landvaultbackend.checkout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/reservations}. One field, deliberately: the estate, the
 * seller and every price figure are derived server-side from the plot.
 * <p>
 * The frontend also sends {@code listingId}; it is ignored rather than
 * trusted, since the estate a plot belongs to is a fact this backend already
 * holds and a client-supplied one could only ever disagree with it.
 */
@Schema(
        name = "CreateReservationRequest",
        description = """
                Hold one plot for 45 minutes.

                **Only the plot is named.** The estate, the selling company, the tier, the price and \
                the corner premium are all resolved server-side — a client cannot influence what it \
                is about to be charged.""")
public record CreateReservationRequest(

        @Schema(description = "The plot to hold.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID plotId
) {
}
