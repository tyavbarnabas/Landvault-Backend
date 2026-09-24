package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/** An estate's current refund policy. */
@Schema(
        name = "RefundTerms",
        description = """
                What a buyer gets back if they withdraw, and how long it takes.

                **`appliesTo` changes the figure materially** — a deduction taken from the full \
                price is not the same as one taken from what has actually been paid. Both source \
                letters were ambiguous about this, so the developer must state it rather than the \
                platform guessing.

                Fees listed in `nonRefundableFeeTypes` never come back at all and are reported as \
                part of the loss, not netted quietly out of the refund.""")
public record RefundTermsDto(
        int version,
        @Schema(example = "20.00") BigDecimal deductionPct,
        @Schema(description = "Both source letters state 90. Time is part of the cost.", example = "90")
        int processingDays,
        @Schema(description = "`amount_paid` or `total_price`.", example = "total_price")
        String appliesTo,
        @Schema(description = "Fee types never returned under any circumstances.")
        List<String> nonRefundableFeeTypes,
        @Schema(nullable = true) String notes
) {
}
