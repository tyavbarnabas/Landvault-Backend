package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
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
        List<Instalment> instalments,
        @Schema(description = "The late-payment steps and whether penalties are refunded on withdrawal, as "
                + "offered when the purchase opened. No steps: no penalty.")
        LatePaymentTerms lateTerms,
        @Schema(description = "Phase 5c: 1 as offered; each accepted restructure adds one. Paid instalments keep "
                + "their numbers; the new ones carry on from them.")
        int version,
        @Schema(description = "Sell my plot (D25): the days a new buyer held this plot. Unpaid instalments' due "
                + "dates move on by the days held on or after them, so the seller is never late while they can't "
                + "pay. The standing's due dates include it; these are the dates as scheduled.")
        List<Pause> resaleHolds
) {

    public PlanSchedule(UUID planId, String planName, String frequency, BigDecimal planTotal, BigDecimal premium,
                        BigDecimal deposit, List<Instalment> instalments, LatePaymentTerms lateTerms) {
        this(planId, planName, frequency, planTotal, premium, deposit, instalments, lateTerms, 1);
    }

    public PlanSchedule(UUID planId, String planName, String frequency, BigDecimal planTotal, BigDecimal premium,
                        BigDecimal deposit, List<Instalment> instalments, LatePaymentTerms lateTerms, int version) {
        this(planId, planName, frequency, planTotal, premium, deposit, instalments, lateTerms, version, List.of());
    }

    /** The same schedule with other instalments (the due dates moved by a hold). */
    public PlanSchedule withInstalments(List<Instalment> moved) {
        return new PlanSchedule(planId, planName, frequency, planTotal, premium, deposit, moved, lateTerms, version,
                resaleHolds);
    }

    /** A new buyer is holding the plot now. */
    public boolean heldForResale() {
        return resaleHolds.stream().anyMatch(p -> p.until() == null);
    }

    /**
     * Days, Lagos, from {@code from} up to but not including {@code until};
     * {@code until} is null while the hold is still running.
     */
    @Schema(name = "ResaleHoldPause")
    public record Pause(LocalDate from, LocalDate until) {
    }

    /**
     * @param carriedPenalty phase 5c: penalties reached before a restructure, kept and charged with this
     *                       instalment; zero otherwise
     */
    @Schema(name = "ScheduledInstalment")
    public record Instalment(int sequence, LocalDate dueDate, BigDecimal amount,
                             @Schema(description = "Phase 5c: penalties reached before a restructure, payable with "
                                     + "this instalment. Zero otherwise.") BigDecimal carriedPenalty) {

        public Instalment(int sequence, LocalDate dueDate, BigDecimal amount) {
            this(sequence, dueDate, amount, BigDecimal.ZERO);
        }
    }
}
