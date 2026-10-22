package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** An estate's current instalment plans, each worked out for every open price tier. */
@Schema(name = "PaymentPlans")
public record PaymentPlansDto(
        @Schema(description = "0 when never declared (outright only).") int version,
        List<Plan> plans
) {

    @Schema(name = "PaymentPlan")
    public record Plan(
            UUID id,
            String name,
            @Schema(nullable = true) BigDecimal depositPct,
            @Schema(nullable = true) BigDecimal depositAmount,
            int instalmentCount,
            @Schema(description = "`monthly` or `quarterly`.") String frequency,
            BigDecimal premiumPct,
            @Schema(description = "The plan at each open tier's price (a non-corner plot). A tier is left out "
                    + "when a fixed deposit leaves nothing to pay in instalments.")
            List<Quote> quotes
    ) {
    }

    @Schema(name = "PaymentPlanQuote")
    public record Quote(
            UUID tierId,
            String tierLabel,
            String currency,
            @Schema(description = "Paying in full.") BigDecimal price,
            @Schema(description = "Paid in all under the plan.") BigDecimal planTotal,
            @Schema(description = "How much more than paying in full.") BigDecimal premium,
            BigDecimal deposit,
            int instalmentCount,
            @Schema(description = "Each instalment but the last.") BigDecimal instalment,
            @Schema(description = "The last, carrying any leftover kobo.") BigDecimal lastInstalment
    ) {
    }
}
