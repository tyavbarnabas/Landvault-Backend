package com.techcomfort.landvaultbackend.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What a plot actually costs, and what leaving costs — computed, in naira,
 * never expressed as a percentage and left for the buyer to work out.
 * <p>
 * <strong>A percentage is not a disclosure.</strong> "20% administrative
 * charge" is abstract; "you receive ₦4,800,000 — a loss of ₦1,200,000 —
 * after about 90 days" is something a person reacts to. Same discipline the
 * upgrade engine already applies to its signed delta, and the reason
 * {@link PlotPricing} computes a corner premium rather than publishing a
 * rate.
 * <p>
 * Pure arithmetic over {@link BigDecimal}, in {@code common} beside
 * {@code PlotPricing} for the same reason: {@code inventory} shows a
 * developer what they have declared and {@code marketplace} shows a buyer
 * the same figures, and two implementations of one sum eventually disagree
 * about money.
 * <p>
 * This class computes. <strong>It does not judge.</strong> Nothing here caps,
 * warns on, or flags an amount as excessive — a 156% infrastructure fee
 * computes exactly like a 5% one. The platform discloses; it does not
 * regulate.
 */
public final class CommitmentCalculator {

    /** Matches {@code price_tiers.price} and the fee columns — numeric(19,4). */
    private static final int MONEY_SCALE = 4;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CommitmentCalculator() {
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    /**
     * One declared charge, flattened to what the arithmetic needs. A fixed
     * fee has {@code min} equal to {@code max}; a variable one carries its
     * declared range and is never collapsed to a midpoint.
     */
    public record DeclaredFee(
            FeeType type,
            String label,
            String currency,
            BigDecimal min,
            BigDecimal max,
            boolean fixed,
            boolean mandatory,
            DueTrigger dueTrigger
    ) {
        public boolean isRange() {
            return !fixed && min != null && max != null && min.compareTo(max) != 0;
        }
    }

    // ------------------------------------------------------------------
    // Outputs
    // ------------------------------------------------------------------

    /**
     * An amount that may not be a single number. {@code min} equals
     * {@code max} for anything fixed — callers render a range only when they
     * differ, and <strong>never a midpoint</strong>: a midpoint is a figure
     * nobody promised.
     */
    public record Money(BigDecimal min, BigDecimal max) {

        public static final Money ZERO = new Money(BigDecimal.ZERO, BigDecimal.ZERO);

        public boolean isRange() {
            return min.compareTo(max) != 0;
        }

        Money plus(Money other) {
            return new Money(min.add(other.min), max.add(other.max));
        }

        Money scaled() {
            return new Money(
                    min.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    max.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
    }

    /**
     * What one price tier really commits a buyer to.
     *
     * @param landPrice        the advertised figure, and the only one most
     *                         listings show anywhere else
     * @param oneOffFees       mandatory charges falling due once
     * @param totalCommitment  {@code landPrice + oneOffFees} — the number
     *                         this whole feature exists to put in front of a
     *                         buyer before they commit
     * @param totalIfCorner    the same for a corner plot, null when the
     *                         estate charges no corner premium. Not an
     *                         upsell: a buyer choosing a corner plot pays a
     *                         different real total and deserves to see it.
     * @param recurringFees    charges that repeat — deliberately outside the
     *                         total; see {@link DueTrigger#ANNUAL}
     * @param optionalFees     genuinely avoidable charges, outside the total
     *                         so it is not inflated by things a buyer may
     *                         never incur
     * @param excludedCurrency fees denominated in some other currency, which
     *                         are never summed into anything
     */
    public record TierCommitment(
            BigDecimal landPrice,
            String currency,
            Money oneOffFees,
            Money totalCommitment,
            Money totalIfCorner,
            Money recurringFees,
            Money optionalFees,
            List<DeclaredFee> excludedCurrency
    ) {
        /**
         * True when the advertised price is not the whole story — which, in
         * both source letters, it never was.
         */
        public boolean hasAdditionalCost() {
            return oneOffFees.max().signum() > 0
                    || recurringFees.max().signum() > 0
                    || optionalFees.max().signum() > 0
                    || !excludedCurrency.isEmpty();
        }
    }

    /** What withdrawing actually returns, and what it costs. */
    public record RefundOutcome(
            BigDecimal basis,
            BigDecimal deductionPct,
            BigDecimal deduction,
            BigDecimal nonRefundableFees,
            BigDecimal refundAmount,
            BigDecimal totalLoss,
            int processingDays,
            String currency
    ) {
    }

    /** One rung of a late-payment escalation, in naira rather than percent. */
    public record PenaltyStep(int monthsLate, BigDecimal penaltyPct, BigDecimal amount) {
    }

    // ------------------------------------------------------------------
    // Total commitment (FD-2)
    // ------------------------------------------------------------------

    /**
     * Per tier, never per estate: an estate's tiers carry different prices,
     * so one estate-level "commitment" would be a figure that applies to
     * nobody.
     * <p>
     * <strong>Recurring charges are excluded from the total</strong>, and
     * that is what the source letters' own stated totals do: Double King's
     * ₦10,010,000 and Top Rank's ₦12,610,000 are both land plus one-off fees,
     * with the annual facility fee named separately. Folding one year of a
     * perpetual charge into a purchase price would be arbitrary and would
     * misstate both.
     * <p>
     * <strong>Optional charges are excluded too</strong> — but note that a
     * fee whose condition is itself mandatory must have been declared
     * mandatory (FD-3), so what remains here is genuinely avoidable.
     * <p>
     * <strong>Fees in another currency are never summed in.</strong> Adding a
     * dollar fee to a naira price produces a number that is wrong in every
     * currency; they come back in {@code excludedCurrency} instead, so the
     * omission is visible rather than silent.
     */
    public static TierCommitment forTier(
            BigDecimal landPrice,
            String currency,
            BigDecimal cornerPremiumPct,
            List<DeclaredFee> fees) {

        Money oneOff = Money.ZERO;
        Money recurring = Money.ZERO;
        Money optional = Money.ZERO;
        List<DeclaredFee> otherCurrency = new ArrayList<>();

        for (DeclaredFee fee : fees == null ? List.<DeclaredFee>of() : fees) {
            if (!currency.equalsIgnoreCase(fee.currency())) {
                otherCurrency.add(fee);
                continue;
            }
            Money amount = new Money(nullToZero(fee.min()), nullToZero(fee.max()));
            if (fee.dueTrigger() != null && fee.dueTrigger().isRecurring()) {
                recurring = recurring.plus(amount);
            } else if (!fee.mandatory()) {
                optional = optional.plus(amount);
            } else {
                oneOff = oneOff.plus(amount);
            }
        }

        Money total = new Money(landPrice, landPrice).plus(oneOff);

        Money totalIfCorner = null;
        if (cornerPremiumPct != null && cornerPremiumPct.signum() > 0) {
            BigDecimal cornerLandPrice = PlotPricing.price(landPrice, true, cornerPremiumPct);
            totalIfCorner = new Money(cornerLandPrice, cornerLandPrice).plus(oneOff).scaled();
        }

        return new TierCommitment(
                landPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                currency,
                oneOff.scaled(),
                total.scaled(),
                totalIfCorner,
                recurring.scaled(),
                optional.scaled(),
                List.copyOf(otherCurrency));
    }

    // ------------------------------------------------------------------
    // Refunds (RF-1)
    // ------------------------------------------------------------------

    /**
     * What a buyer gets back if they withdraw, computed against the
     * <strong>headline price</strong>.
     * <p>
     * That basis is deliberate and limited: computing against what has
     * actually been paid (RF-3) needs payment records, and nothing tracks
     * payments yet. The figure produced here is therefore the full-payment
     * scenario — a buyer's maximum exposure — and must be labelled as such
     * rather than presented as their personal outcome.
     * <p>
     * Non-refundable fees are subtracted from nothing: they were never
     * coming back, so they are reported as part of the loss and excluded
     * from the returned amount. Double King's ₦10,000 application fee is
     * explicitly one of these.
     */
    public static RefundOutcome refundOn(
            BigDecimal basis,
            String currency,
            BigDecimal deductionPct,
            int processingDays,
            BigDecimal nonRefundableFeeTotal) {

        BigDecimal deduction = basis
                .multiply(deductionPct.divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal nonRefundable = nullToZero(nonRefundableFeeTotal)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new RefundOutcome(
                basis.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                deductionPct,
                deduction,
                nonRefundable,
                basis.subtract(deduction).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                deduction.add(nonRefundable),
                processingDays,
                currency);
    }

    // ------------------------------------------------------------------
    // Penalties (DF-1)
    // ------------------------------------------------------------------

    /** A percentage schedule turned into the naira it actually costs. */
    public static PenaltyStep penaltyStep(int monthsLate, BigDecimal penaltyPct, BigDecimal basis) {
        BigDecimal amount = basis
                .multiply(penaltyPct.divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new PenaltyStep(monthsLate, penaltyPct, amount);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
