package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The flexible plan a purchase was opened on, copied when it opened and never
 * recomputed — the price rule, applied to the plan's terms. No schedule: any
 * amount, any time. See AGENTS.md, "Flexible contribution".
 */
@Schema(name = "FlexiblePurchase", description = "The flexible plan this purchase was opened on, as offered then.")
public record FlexiblePurchase(
        UUID planId,
        String planName,
        @Schema(description = "What finishing the plan costs: the agreed price plus the premium.") BigDecimal planTotal,
        @Schema(description = "How much more than paying in full.") BigDecimal premium,
        @Schema(description = "The first payment's minimum; once finance verifies it the plot is held for the buyer.")
        BigDecimal minimumToStart,
        @Schema(nullable = true, description = "The smallest later payment; the one that finishes may be smaller. "
                + "Null when none.") BigDecimal minimumContribution,
        @Schema(description = "What the developer keeps if the buyer leaves, as a share of `exitAppliesTo`.")
        BigDecimal exitDeductionPct,
        @Schema(description = "`amount_paid` or `total_price` (the agreed price).") RefundAppliesTo exitAppliesTo,
        @Schema(description = "The share of any gain the developer keeps — on leaving, or on a sale the buyer asks "
                + "for.") BigDecimal developerGainSharePct,
        @Schema(description = "Months with no verified payment before the developer may give notice.")
        int inactivityMonths,
        @Schema(description = "The notice before a dormant plan may be ended.") int noticeDays,
        @Schema(nullable = true, description = "Months from the offer date to finish; null when there is no limit.")
        Integer maxMonths,
        @Schema(nullable = true, description = "The date to finish by, in Lagos; null when there is no limit.")
        LocalDate finishBy
) {
}
