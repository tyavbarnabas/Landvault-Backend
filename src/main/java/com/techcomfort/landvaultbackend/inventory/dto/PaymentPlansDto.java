package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
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
            @Schema(description = "`installment` or `flexible`.") String kind,
            @Schema(nullable = true, description = "The deposit as a share of the plan total; on a flexible plan, the "
                    + "minimum to start.") BigDecimal depositPct,
            @Schema(nullable = true) BigDecimal depositAmount,
            @Schema(nullable = true, description = "Null on a flexible plan.") Integer instalmentCount,
            @Schema(nullable = true, description = "`monthly` or `quarterly`; null on a flexible plan.") String frequency,
            BigDecimal premiumPct,
            @Schema(nullable = true, description = "A flexible plan's terms; null on an instalment plan.")
            FlexibleTerms flexible,
            @Schema(description = "The plan at each open tier's price (a non-corner plot). A tier is left out "
                    + "when a fixed deposit leaves nothing to pay in instalments.")
            List<Quote> quotes
    ) {
    }

    @Schema(name = "FlexiblePlanTerms")
    public record FlexibleTerms(
            @Schema(nullable = true) BigDecimal minimumContribution,
            BigDecimal exitDeductionPct,
            @Schema(description = "`amount_paid` or `total_price`.") String exitAppliesTo,
            @Schema(description = "The share of any gain on the next sale you keep.") BigDecimal developerGainSharePct,
            int inactivityMonths,
            int noticeDays,
            @Schema(nullable = true) Integer maxMonths
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
            @Schema(description = "The deposit; on a flexible plan, the minimum to start.") BigDecimal deposit,
            @Schema(nullable = true) Integer instalmentCount,
            @Schema(nullable = true, description = "Each instalment but the last.") BigDecimal instalment,
            @Schema(nullable = true, description = "The last, carrying any leftover kobo.") BigDecimal lastInstalment,
            @Schema(nullable = true, description = "A flexible plan in naira: leaving, the gain, the clocks. Null on "
                    + "an instalment plan.") FlexiblePlanCalculator.Quote flexible
    ) {
    }
}
