package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;

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

        @Schema(description = "`installment` or `flexible` — always set, whatever the purchase's status, so an "
                + "ended plan can still be told apart.", allowableValues = {"installment", "flexible"})
        String kind,

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

        @Schema(description = "Notice can be given today: an instalment is unpaid long enough (on a flexible plan, "
                + "it has gone quiet). A payment waiting for your finance to verify counts as paid. False while a "
                + "notice is already running.")
        boolean revocableNow,

        @Schema(description = "What is overdue today, penalties included; zero when nothing is.")
        BigDecimal overdueToday,

        @Schema(description = "The open notice, if one has been given.", nullable = true)
        Notice notice,

        @Schema(description = "While the plan runs: what revoking it now would return to the buyer and what you "
                + "keep — exactly a withdrawal's figures. Once revoked or withdrawn: what was settled. Null otherwise.",
                nullable = true)
        Settlement settlement,

        @Schema(description = "Every notice on this plan, newest first, including the open one.")
        List<Notice> history,

        @Schema(description = "A flexible plan (phase 4): where it stands — `noticeReason` says why notice may be "
                + "given today. `afterMonthsUnpaid` is then the plan's inactivity period, `revocableFrom` the day it "
                + "would count as quiet (or the day after its date to finish by), and `overdueToday` zero: nothing on "
                + "a flexible plan is ever overdue. Null on an instalment plan, and once a flexible plan has ended (`kind` "
                + "still says `flexible`; `settlement` says what was paid and returned).", nullable = true)
        FlexibleStanding flexible
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
            @Schema(description = "The instalment that made the plan revocable when notice was given; null on a "
                    + "flexible plan.", nullable = true)
            Integer instalmentSequence,
            @Schema(description = "Why notice was given: `arrears` (an instalment plan), `inactivity` or `finish_by` "
                    + "(a flexible plan).") String trigger,
            @Schema(nullable = true) Instant closedAt,
            @Schema(nullable = true) String closeNote
    ) {
    }

    @Schema(name = "PlanRevocationSettlement")
    public record Settlement(BigDecimal paid, BigDecimal refund, BigDecimal developerKeeps, String currency) {
    }
}
