package com.techcomfort.landvaultbackend.marketplace.dto;

import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One instalment plan at one tier's price (PL-2), worked out by HomeTrue from
 * the developer's declared terms — never typed in. For a non-corner plot; a
 * corner plot's figures follow from its higher price at checkout.
 */
@Schema(name = "MarketplacePaymentPlan")
public record MarketplacePaymentPlanDto(
        UUID planId,
        @Schema(example = "12-month plan") String name,
        @Schema(nullable = true, description = "`monthly` or `quarterly`; null on a flexible plan.") String frequency,
        @Schema(description = "Paid now, before the plot is held on the plan; on a flexible plan, the minimum to "
                + "start.") BigDecimal deposit,
        @Schema(nullable = true, description = "Null on a flexible plan.") Integer instalmentCount,
        @Schema(nullable = true, description = "Each instalment but the last.") BigDecimal instalment,
        @Schema(nullable = true, description = "The last, carrying any leftover kobo.") BigDecimal lastInstalment,
        @Schema(description = "Paid in all under the plan.") BigDecimal planTotal,
        @Schema(description = "How much more than paying in full, in money.") BigDecimal premium,
        @Schema(description = "The estate's declared late-payment steps on ONE instalment of this plan, in "
                + "naira: the highest step reached applies, steps don't add up. Empty when none are declared, and "
                + "always on a flexible plan, which has nothing to be late for.")
        List<ExitCostsDto.PenaltyStepDto> ifAnInstalmentIsLate,
        @Schema(description = "`installment` or `flexible`.") String kind,
        @Schema(nullable = true, description = "A flexible plan in naira — any amount, any time, no due dates: "
                + "what leaving returns, who keeps any gain, and the clocks. Null on an instalment plan.")
        FlexiblePlanCalculator.Quote flexible
) {
}
