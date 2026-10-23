package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5b: where a plan stands for revocation — the terms copied onto it,
 * whether it can be revoked today, the open notice, what a revocation would
 * return, and every earlier notice.
 */
@Schema(name = "PlanRevocation")
public record RevocationDto(
        UUID transactionId,
        String transactionReference,

        @Schema(description = "The purchase's status: `on_plan`, or `revoked` once confirmed (also `withdrawn`, "
                + "`verified` for a plan that ended another way).")
        String status,

        @Schema(description = "Revocable once an instalment is unpaid this many whole months past its due date, "
                + "as offered when the purchase opened.")
        int afterMonthsUnpaid,

        @Schema(description = "The notice the buyer is given, as offered when the purchase opened.")
        int noticeDays,

        @Schema(description = "The day the oldest late instalment makes the plan revocable if still unpaid; null "
                + "while nothing is late.", nullable = true)
        LocalDate revocableFrom,

        @Schema(description = "Notice can be given today: an instalment is unpaid long enough. A payment waiting "
                + "for your finance to verify counts as paid.")
        boolean revocableNow,

        @Schema(description = "What is overdue today, penalties included; zero when nothing is.")
        BigDecimal overdueToday,

        @Schema(description = "The open notice, if one has been given.", nullable = true)
        Notice notice,

        @Schema(description = "While the plan runs: what revoking it now would return to the buyer and what you "
                + "keep — exactly a withdrawal's figures. Once revoked: what was settled. Null otherwise.",
                nullable = true)
        Settlement settlement,

        @Schema(description = "Every notice on this plan, newest first, including the open one.")
        List<Notice> history
) {

    /**
     * @param status `notice` (open), `cancelled`, `cleared` (the buyer caught up), `withdrawn` (the buyer
     *               withdrew) or `revoked`
     */
    @Schema(name = "PlanRevocationNotice")
    public record Notice(
            UUID id,
            @Schema(description = "`notice` (open), `cancelled`, `cleared` (the buyer caught up), `withdrawn` "
                    + "(the buyer withdrew) or `revoked`.") String status,
            @Schema(description = "Your reason, as the buyer was told it.") String reason,
            Instant givenAt,
            @Schema(description = "The last day (Lagos) the buyer may pay what is overdue.") LocalDate deadline,
            @Schema(description = "The day the revocation can be confirmed, if the buyer hasn't caught up.")
            LocalDate confirmableFrom,
            @Schema(description = "The instalment that made the plan revocable when notice was given.")
            int instalmentSequence,
            @Schema(nullable = true) Instant closedAt,
            @Schema(nullable = true) String closeNote
    ) {
    }

    @Schema(name = "PlanRevocationSettlement")
    public record Settlement(BigDecimal paid, BigDecimal refund, BigDecimal developerKeeps, String currency) {
    }
}
