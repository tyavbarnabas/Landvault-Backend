package com.techcomfort.landvaultbackend.marketplace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What leaving costs, by either route, shown together (DF-3).
 * <p>
 * Neither clause is hidden on its own. In the source letters a buyer could
 * read the penalty schedule and the refund policy and still not notice that
 * <em>every</em> exit costs them — falling behind is penalised up to 20%,
 * withdrawing forfeits 20%. The trap is only visible when both are on the
 * same screen, which is the only thing this type does.
 * <p>
 * The platform does not forbid such terms and does not call them unfair. It
 * makes sure the buyer sees both sides before stepping in.
 */
@Schema(
        name = "ExitCosts",
        description = """
                Both ways out of this purchase, priced in naira.

                **Computed against one named tier** (`basisTierLabel`), because the figures depend \
                on the price. It is an illustration of that tier, not a statement about what any \
                particular buyer has paid — nothing here knows that.

                `ifYouWithdraw` assumes payment in full, which is a buyer's maximum exposure. A \
                refund computed against what has actually been paid needs payment records, which \
                do not exist yet.

                **`bothPathsCarryACost` is arithmetic, not a judgement**: it is true when falling \
                behind is penalised *and* withdrawing forfeits something, i.e. when there is no \
                free exit.""")
public record ExitCostsDto(
        @Schema(description = "The tier these figures are computed against.") UUID basisTierId,
        String basisTierLabel,
        BigDecimal basisLandPrice,
        String currency,

        @Schema(description = "Assumes payment in full — a maximum exposure, not a personal figure.")
        RefundOutcomeDto ifYouWithdraw,

        @Schema(description = """
                Late-payment penalties in naira, on **one instalment** of `penaltyBasisPlan` at this tier \
                (decided with the user: a penalty is a share of the overdue instalment, the highest step \
                reached applying). Empty when the estate offers no instalment plans — an outright buyer \
                has nothing to fall behind on. Each plan's own figures are on the tier's `paymentPlans`.""")
        List<PenaltyStepDto> ifYouFallBehind,

        @Schema(description = "The plan `ifYouFallBehind` is worked out on; null when there are no plans.",
                nullable = true)
        String penaltyBasisPlan,

        @Schema(description = "The instalment those penalties are a share of.", nullable = true)
        BigDecimal penaltyBasisInstalment,

        @Schema(description = "Whether late-payment penalties are refunded, on the refund terms, to a buyer "
                + "who withdraws — declared by the developer. Null when no penalty is declared.", nullable = true)
        Boolean penaltiesRefundable,

        @Schema(description = "What triggers revocation, the notice given, and what happens to money "
                + "already paid.")
        RevocationDto revocation,

        @Schema(description = "True when neither exit is free.") boolean bothPathsCarryACost
) {

    @Schema(name = "RefundOutcome",
            description = "What withdrawing returns. A percentage is not a disclosure; these are the "
                    + "actual amounts.")
    public record RefundOutcomeDto(
            @Schema(description = "What the figures are computed against.") BigDecimal basis,
            @Schema(example = "20.00") BigDecimal deductionPct,
            @Schema(description = "The administrative charge, in naira.") BigDecimal deduction,
            @Schema(description = "Fees that are never returned regardless, e.g. an application fee.")
            BigDecimal nonRefundableFees,
            @Schema(description = "What actually comes back.") BigDecimal refundAmount,
            @Schema(description = "Deduction plus non-refundable fees.") BigDecimal totalLoss,
            @Schema(description = "How long it takes. Time is part of the cost.", example = "90")
            int processingDays
    ) {
    }

    @Schema(name = "PenaltyStep", description = "One rung of a late-payment escalation, priced.")
    public record PenaltyStepDto(
            @Schema(example = "3") int monthsLate,
            @Schema(example = "5.00") BigDecimal penaltyPct,
            @Schema(description = "What that percentage actually costs.") BigDecimal amount
    ) {
    }

    @Schema(name = "RevocationTerms")
    public record RevocationDto(
            String trigger,
            @Schema(nullable = true) Integer noticeDays,
            @Schema(description = "What happens to money already paid.") String onRevocationRefund,
            @Schema(description = "Declared, but nothing tracks the clock yet.", nullable = true)
            Integer developmentDeadlineMonths,
            @Schema(description = "Disclosed here; not enforced, because resale does not exist yet.")
            boolean transferRequiresConsent
    ) {
    }
}
