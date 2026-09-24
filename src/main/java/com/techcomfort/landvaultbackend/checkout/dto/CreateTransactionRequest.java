package com.techcomfort.landvaultbackend.checkout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/checkout/transactions}.
 * <p>
 * <strong>Carries no money.</strong> The frontend's current
 * {@code initiateTransaction} posts {@code basePrice}, {@code totalPrice},
 * {@code amountDue}, {@code cornerPremiumPct}, {@code sizeSqm} and
 * {@code titleType} from the browser; none of them is accepted here. A
 * client-supplied price is the same class of hole as a client-supplied
 * {@code tenantId} — it lets the payer name their own figure. Everything
 * monetary is computed server-side and captured at reservation.
 */
@Schema(
        name = "CreateTransactionRequest",
        description = """
                Open a pending purchase against a hold you already own.

                **No price fields.** Everything monetary was computed server-side and captured when \
                the plot was held; a price sent by the client is ignored, not applied. What the \
                buyer chooses is how they intend to use the land and how they intend to pay.

                One transaction per reservation — a repeat POST is a duplicate, not a second \
                purchase.""")
public record CreateTransactionRequest(

        @Schema(description = "A hold this buyer currently owns, still active.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID reservationId,

        @Schema(description = "`development` (build on it) or `investment` (hold it). The buyer's "
                + "intent, not the seller's listing intent.",
                example = "development", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String intent,

        @Schema(description = "`outright`, `installment` or `milestone`. Recorded here; the schedule "
                + "itself belongs to finance, which is not built.",
                example = "outright", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String plan,

        @Schema(description = "Required for `installment`, and rejected for any other plan.",
                example = "12", nullable = true)
        Integer installmentMonths
) {
}
