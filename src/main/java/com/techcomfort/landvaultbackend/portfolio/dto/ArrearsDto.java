package com.techcomfort.landvaultbackend.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The frontend's {@code ArrearsInfo}, from phase 4: present while an
 * instalment is overdue (within the 7-day grace) or in arrears. Worked out on
 * every read, never stored.
 */
@Schema(name = "Arrears")
public record ArrearsDto(
        @Schema(description = "`overdue` (1–7 days late) or `in_arrears` (more than 7 days).") String status,
        @Schema(description = "The late instalments, penalties aside.") BigDecimal amountOwed,
        @Schema(description = "The oldest late instalment's due date.") LocalDate overdueSinceDate,
        int gracePeriodDays,
        @Schema(description = "That instalment's last day of grace; it is in arrears from the next day.")
        LocalDate graceEndsDate,
        @Schema(description = "Penalties reached today on the late instalments; zero when none is declared or "
                + "reached yet. Charged with the instalment when it is paid.") BigDecimal penaltyAmount,
        @Schema(description = "Which step applies and why, in plain words; null when there is no penalty.",
                nullable = true) String penaltyReason,
        int instalmentsLate,
        @Schema(description = "`amountOwed` plus `penaltyAmount`.") BigDecimal totalDue,
        @Schema(description = "Days since the oldest late instalment fell due.") int daysLate
) {
}
