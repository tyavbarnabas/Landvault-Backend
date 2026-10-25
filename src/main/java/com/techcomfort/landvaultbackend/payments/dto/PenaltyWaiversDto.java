package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A plan's late-payment penalties that can still be waived today, and the waivers already made. */
@Schema(name = "PenaltyWaivers")
public record PenaltyWaiversDto(
        UUID transactionId,
        String transactionReference,
        @Schema(description = "The purchase's status; waivers are possible while `on_plan`.") String planStatus,
        String currency,
        @Schema(description = "Unpaid instalments carrying a penalty today, oldest first.") List<Penalty> penalties,
        @Schema(description = "Every waiver on this plan, newest first.") List<Waiver> waivers
) {

    /**
     * @param reached     the penalty reached today, including any carried by a restructure — before waivers
     * @param waived      waived so far on this instalment
     * @param outstanding what is left — the most a new waiver can be
     */
    @Schema(name = "WaivablePenalty")
    public record Penalty(int instalmentSequence, LocalDate dueDate,
                          @Schema(description = "`overdue`, `in_arrears`, `upcoming` or `due_today` (a carried "
                                  + "penalty).") String instalmentStatus,
                          BigDecimal reached, BigDecimal waived, BigDecimal outstanding) {
    }

    @Schema(name = "PenaltyWaiver")
    public record Waiver(UUID id, int instalmentSequence, int scheduleVersion, BigDecimal amount, String reason,
                         Instant waivedAt, UUID waivedBy) {
    }
}
