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

        @Schema(description = "`outright`, `installment` or `milestone`. For `installment`, name the "
                + "estate's declared plan in `planId`; the schedule is worked out from it.",
                example = "outright", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String plan,

        @Schema(description = "**No longer chosen by the buyer**: it comes from the plan. Must be left out.",
                nullable = true, deprecated = true)
        Integer installmentMonths,

        @Schema(description = "Required for `installment`: one of the plans the estate currently offers "
                + "(`priceTiers[].paymentPlans[].planId` on the listing). Rejected for any other plan.",
                nullable = true)
        UUID planId
) {
}
