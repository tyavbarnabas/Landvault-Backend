package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One rung of a late-payment escalation. The buyer-facing surface adds the
 * naira amount; here the developer sees what they declared.
 */
@Schema(name = "PenaltyTier", description = "A late-payment penalty at a given number of months overdue.")
public record PenaltyTierDto(
        @Schema(example = "3") int monthsLate,
        @Schema(example = "5.00") BigDecimal penaltyPct
) {
}
