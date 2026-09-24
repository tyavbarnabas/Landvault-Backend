package com.techcomfort.landvaultbackend.checkout.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A hold, as its own buyer sees it. {@code status} is a wire string rather
 * than the internal enum — see AGENTS.md on DTO boundaries.
 */
@Schema(
        name = "Reservation",
        description = """
                A 45-minute hold on one plot.

                **Holding is not owning.** The plot is taken off the market for the window; it is \
                not allocated, and nothing here says it is sold.

                `secondsRemaining` is computed per response so a countdown never has to trust the \
                client's clock, and reaches 0 rather than going negative. **A hold cannot be \
                extended** — the window is the same for everyone, including whoever is waiting \
                behind this buyer.

                Expiry does not depend on the browser staying open: a closed laptop still returns \
                the plot.""")
public record ReservationDto(

        UUID id,

        @Schema(description = "The estate the plot belongs to (the frontend calls this the listing).")
        UUID estateId,

        UUID plotId,

        @Schema(description = "The tier this plot is priced by.")
        UUID priceTierId,

        @Schema(description = "`active`, `expired`, `released` or `converted`.", example = "active")
        String status,

        Instant expiresAt,

        @Schema(description = "Seconds left on the hold, floored at 0. Computed server-side.",
                example = "2700")
        long secondsRemaining
) {
}
