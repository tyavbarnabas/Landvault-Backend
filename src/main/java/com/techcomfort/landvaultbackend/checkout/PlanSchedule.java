package com.techcomfort.landvaultbackend.checkout;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The plan an instalment purchase was opened on and its schedule, worked out
 * from the agreed price when the purchase was opened and never recomputed.
 */
@Schema(name = "PlanSchedule")
public record PlanSchedule(
        UUID planId,
        String planName,
        @Schema(description = "`monthly` or `quarterly`.") String frequency,
        @Schema(description = "Paid in all under the plan: the agreed price plus the premium.") BigDecimal planTotal,
        @Schema(description = "How much more than paying in full.") BigDecimal premium,
        @Schema(description = "Paid first, before the plot is held on the plan.") BigDecimal deposit,
        List<Instalment> instalments
) {

    @Schema(name = "ScheduledInstalment")
    public record Instalment(int sequence, LocalDate dueDate, BigDecimal amount) {
    }
}
