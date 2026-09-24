package com.techcomfort.landvaultbackend.checkout.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A pending purchase. All three money figures are returned, not just the
 * total — a corner plot's price otherwise matches no tier on the price list
 * with nothing to explain the difference, the same reasoning as
 * {@code PlotDetailDto}.
 */
@Schema(
        name = "Transaction",
        description = """
                A purchase in progress.

                **`pending_payment` is the only status this endpoint produces, and a reservation \
                never allocates.** The plot is held, not sold. Allocation happens only after a \
                finance-role human verifies the payment — a gateway signal is never treated as \
                confirmation. Nothing here should be shown to a buyer as complete.

                **The price was captured when the plot was held** and is not recomputed: if the \
                developer re-prices the tier during checkout, this figure does not move. \
                `basePrice` is the tier's own price, `cornerPremiumPct` is null unless a premium \
                actually applied, and `totalPrice` is what was agreed.

                Finance, payment methods, schedules and receipts are not built.""")
public record TransactionDto(

        UUID id,

        @Schema(description = "Human-quotable on a transfer or a support call.", example = "LV-4XK2P9TQ")
        String reference,

        UUID estateId,
        UUID plotId,
        UUID reservationId,

        @Schema(description = "The tier's own price at the moment of reservation.")
        BigDecimal basePrice,

        @Schema(description = "Null on a non-corner plot, never 0.", nullable = true)
        BigDecimal cornerPremiumPct,

        @Schema(description = "What the buyer agreed to pay.")
        BigDecimal totalPrice,

        @Schema(example = "NGN") String currency,

        @Schema(description = "`development` or `investment`.", example = "development")
        String intent,

        @Schema(description = "`outright`, `installment` or `milestone`.", example = "outright")
        String plan,

        @Schema(nullable = true) Integer installmentMonths,

        @Schema(description = "Always `pending_payment` from this module.", example = "pending_payment")
        String status,

        Instant createdAt
) {
}
