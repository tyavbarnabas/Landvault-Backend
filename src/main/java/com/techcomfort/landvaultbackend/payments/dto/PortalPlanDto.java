package com.techcomfort.landvaultbackend.payments.dto;

import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 4: one of a developer's running instalment plans and where it stands
 * today — worked out on read, never stored.
 */
@Schema(name = "PortalPlan")
public record PortalPlanDto(
        UUID transactionId,
        String transactionReference,
        UUID estateId,
        String estateName,
        UUID plotId,
        @Schema(example = "Block A, Plot 10") String plotLabel,
        String buyerEmail,
        String planName,
        String currency,
        int instalmentCount,
        @Schema(description = "Verified by finance.") int instalmentsPaid,
        @Schema(description = "The first unpaid instalment; null once nothing is owed.", nullable = true)
        LocalDate nextDueDate,
        @Schema(nullable = true) BigDecimal nextDueAmount,
        @Schema(description = "`on_track`, `due_today`, `overdue`, `in_arrears`. Same as the buyer sees.")
        String status,
        @Schema(description = "While an instalment is late: what's late, the penalty reached, the total due now.",
                nullable = true)
        PlanStanding.Arrears arrears
) {
}
