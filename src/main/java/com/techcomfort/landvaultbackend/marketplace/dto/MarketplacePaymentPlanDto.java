package com.techcomfort.landvaultbackend.marketplace.dto;

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
        @Schema(description = "`monthly` or `quarterly`.") String frequency,
        @Schema(description = "Paid now, before the plot is held on the plan.") BigDecimal deposit,
        int instalmentCount,
        @Schema(description = "Each instalment but the last.") BigDecimal instalment,
        @Schema(description = "The last, carrying any leftover kobo.") BigDecimal lastInstalment,
        @Schema(description = "Paid in all under the plan.") BigDecimal planTotal,
        @Schema(description = "How much more than paying in full, in money.") BigDecimal premium,
        @Schema(description = "The estate's declared late-payment steps on ONE instalment of this plan, in "
                + "naira: the highest step reached applies, steps don't add up. Empty when none are declared.")
        List<ExitCostsDto.PenaltyStepDto> ifAnInstalmentIsLate
) {
}
