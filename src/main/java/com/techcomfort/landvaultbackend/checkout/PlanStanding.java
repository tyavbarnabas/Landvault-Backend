package com.techcomfort.landvaultbackend.checkout;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Where an instalment plan stands today (phase 4), worked out on every read
 * from its schedule, its payments and today's date in Lagos — never stored.
 * Computed by the payments module ({@link PaymentSummaryProvider}).
 */
@Schema(name = "PlanStanding")
public record PlanStanding(
        @Schema(description = "`on_track`, `due_today`, `overdue` (an instalment 1–7 days late), `in_arrears` "
                + "(more than 7 days late) or `paid_off`. The most urgent instalment decides.")
        String status,
        List<InstalmentStatus> instalments,
        @Schema(description = "Present while an instalment is overdue or in arrears; null otherwise.",
                nullable = true)
        Arrears arrears,
        @Schema(description = "Phase 5b: when this plan could be revoked, and any notice given. Null when the plan "
                + "has no revocation terms or is paid off.", nullable = true)
        Revocation revocation,
        @Schema(description = "Phase 5c: `requested` (waiting for the developer) or `proposed` (waiting for the buyer "
                + "to accept) while a restructure is open; null otherwise. Details at "
                + "`/api/transactions/{id}/restructure`.", nullable = true)
        String restructureStatus
) {

    public PlanStanding(String status, List<InstalmentStatus> instalments, Arrears arrears) {
        this(status, instalments, arrears, null, null);
    }

    public PlanStanding(String status, List<InstalmentStatus> instalments, Arrears arrears, Revocation revocation) {
        this(status, instalments, arrears, revocation, null);
    }

    /**
     * Phase 5b, worked out on every read except the notice, which is a record.
     *
     * @param revocableFrom the day the oldest late instalment makes the plan revocable if still unpaid;
     *                      null while nothing is late
     * @param revocableNow  that day has come and the instalment is still unpaid (a payment waiting for
     *                      finance counts as paid)
     * @param notice        the open notice of revocation, if one has been given
     */
    @Schema(name = "PlanRevocationStanding")
    public record Revocation(
            @Schema(description = "Revocable once an instalment is unpaid this many whole months past its due date.")
            int afterMonthsUnpaid,
            @Schema(description = "The notice the buyer is given before a revocation can be confirmed.")
            int noticeDays,
            @Schema(nullable = true) LocalDate revocableFrom,
            boolean revocableNow,
            @Schema(nullable = true) Notice notice
    ) {
    }

    /** An open notice: the buyer has until {@code deadline} (inclusive, Lagos) to pay what is overdue. */
    @Schema(name = "RevocationNotice")
    public record Notice(String reason, Instant givenAt, LocalDate deadline, int instalmentSequence) {
    }

    /**
     * @param status      `upcoming`, `due_today`, `overdue`, `in_arrears`, `awaiting_verification` or `paid`
     * @param daysLate    days past the due date while unpaid; 0 otherwise
     * @param penalty     unpaid: the penalty reached today, which paying now adds; paid or
     *                    awaiting verification: the penalty that was charged with it
     */
    @Schema(name = "InstalmentStatus")
    public record InstalmentStatus(
            int sequence,
            LocalDate dueDate,
            BigDecimal amount,
            @Schema(description = "`upcoming`, `due_today`, `overdue`, `in_arrears`, `awaiting_verification` "
                    + "(paid; finance hasn't verified it — never late) or `paid` (verified).")
            String status,
            int daysLate,
            @Schema(nullable = true) Penalty penalty,
            @Schema(description = "How much of this instalment's penalty the developer waived; `penalty` is already "
                    + "net of it. Null when nothing was waived.", nullable = true)
            BigDecimal penaltyWaived
    ) {
    }

    @Schema(name = "InstalmentPenalty", description = "A late-payment penalty: the declared step reached and its amount.")
    public record Penalty(int monthsLate, BigDecimal penaltyPct, BigDecimal amount) {
    }

    /**
     * What's late and what paying it all now would cost.
     *
     * @param amountOverdue the late instalments, penalties aside
     * @param penaltyAmount the penalties those instalments have reached today
     * @param totalDue      both together
     * @param overdueSince  the oldest late instalment's due date
     * @param graceEnds     that instalment's last day of grace; it is in arrears from the next day
     */
    @Schema(name = "PlanArrears")
    public record Arrears(
            @Schema(description = "`overdue` or `in_arrears`.") String status,
            int instalmentsLate,
            BigDecimal amountOverdue,
            BigDecimal penaltyAmount,
            BigDecimal totalDue,
            LocalDate overdueSince,
            int gracePeriodDays,
            LocalDate graceEnds,
            int daysLate
    ) {
    }
}
