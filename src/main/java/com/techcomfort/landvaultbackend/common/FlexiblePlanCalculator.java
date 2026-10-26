package com.techcomfort.landvaultbackend.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The one place a flexible plan's figures are worked out (see AGENTS.md,
 * "Flexible contribution"): the total, the minimum to start, and — in naira,
 * because percentages alone are not disclosure — what leaving would return
 * and what the buyer would get of any gain. Pure functions over
 * {@link BigDecimal}, like {@link InstalmentPlanCalculator}.
 */
public final class FlexiblePlanCalculator {

    /** HomeTrue's floor on the minimum to start: a plot is never held for a token amount. */
    public static final BigDecimal MIN_START_PCT = BigDecimal.TEN;
    public static final int MIN_INACTIVITY_MONTHS = 6;
    public static final int MIN_NOTICE_DAYS = 30;
    public static final int MIN_MAX_MONTHS = 12;
    /**
     * The rise the worked gain example assumes (FC-8). An example, never a
     * forecast: the developer sets the next price, and may relist at the old one.
     */
    public static final BigDecimal EXAMPLE_RISE_PCT = BigDecimal.valueOf(20);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int KOBO = 2;

    private FlexiblePlanCalculator() {
    }

    /**
     * A flexible plan's terms as declared.
     *
     * @param startPct              the minimum to start, a share of the land price (the premium is not counted)
     * @param minimumContribution   the smallest later payment, except the one that finishes; null for none
     * @param exitDeductionPct      what the developer keeps when the buyer leaves, as a share of {@code exitAppliesTo}
     * @param developerGainSharePct the share of any gain on the next sale the developer keeps
     * @param maxMonths             the maximum time to finish from the offer date; null for none
     */
    public record Terms(BigDecimal startPct, BigDecimal premiumPct, BigDecimal minimumContribution,
                        BigDecimal exitDeductionPct, RefundAppliesTo exitAppliesTo, BigDecimal developerGainSharePct,
                        int inactivityMonths, int noticeDays, Integer maxMonths) {
    }

    @Schema(name = "FlexiblePlanQuote", description = "A flexible plan at one price, in naira.")
    public record Quote(
            @Schema(description = "Paying in full.") BigDecimal price,
            @Schema(description = "The price under this plan, premium included: what finishing it costs.")
            BigDecimal planTotal,
            @Schema(description = "How much more than paying in full.") BigDecimal premium,
            @Schema(description = "Paid first, a share of `price` (the premium is not counted); once finance "
                    + "verifies it the plot is held for the buyer.") BigDecimal minimumToStart,
            @Schema(description = "The smallest later payment; the one that finishes the plan may be smaller. Null "
                    + "when none is declared.", nullable = true) BigDecimal minimumContribution,
            @Schema(description = "What leaving returns at once, from escrow: after paying the minimum to start, "
                    + "and after paying half.") List<Exit> ifYouStopAfterPaying,
            @Schema(description = "If HomeTrue later sells the plot for more than `price`, what the buyer who left "
                    + "gets.") Gain ifSoldForMore,
            @Schema(description = "Months with no verified payment before the developer may give notice.")
            int inactivityMonths,
            @Schema(description = "The notice before the developer may end a dormant plan.") int noticeDays,
            @Schema(description = "Months from the offer date to finish; null when there is no limit.", nullable = true)
            Integer maxMonths
    ) {
    }

    /** @param developerKeeps what the developer keeps of {@code paid}; the rest comes back */
    @Schema(name = "FlexiblePlanExit")
    public record Exit(BigDecimal paid, BigDecimal youGetBack, BigDecimal developerKeeps) {
    }

    /**
     * @param buyerSharePct      the buyer's share of the difference (100 less the developer's)
     * @param perMillionAbove    what the buyer gets for every ₦1,000,000 the next sale is above the price
     */
    @Schema(name = "FlexiblePlanGain")
    public record Gain(BigDecimal developerSharePct, BigDecimal buyerSharePct, BigDecimal perMillionAbove,
                       @Schema(description = "A worked example only, never a forecast.") GainExample example) {
    }

    /**
     * FC-8's worked example: if the next sale were {@link #EXAMPLE_RISE_PCT}
     * above the price. {@code youGet} is zero when the developer keeps it all.
     */
    @Schema(name = "FlexiblePlanGainExample", description = """
            An example only: if HomeTrue sold the plot for `risePct`% more than `price`. Not a forecast — the \
            developer sets the next price, and selling for no more owes nothing.""")
    public record GainExample(BigDecimal risePct, BigDecimal soldFor, BigDecimal difference, BigDecimal youGet) {
    }

    /** The plan at this price. */
    public static Quote quote(BigDecimal price, Terms terms) {
        BigDecimal premiumPct = terms.premiumPct() == null ? BigDecimal.ZERO : terms.premiumPct();
        BigDecimal planTotal = price.add(price.multiply(premiumPct).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(KOBO, RoundingMode.HALF_UP);
        BigDecimal landPrice = price.setScale(KOBO, RoundingMode.HALF_UP);
        BigDecimal start = landPrice.multiply(terms.startPct()).divide(HUNDRED, KOBO, RoundingMode.HALF_UP);
        BigDecimal half = planTotal.divide(BigDecimal.TWO, KOBO, RoundingMode.DOWN);
        BigDecimal buyerShare = HUNDRED.subtract(terms.developerGainSharePct());
        BigDecimal difference = pctOf(EXAMPLE_RISE_PCT, landPrice);
        GainExample example = new GainExample(EXAMPLE_RISE_PCT, landPrice.add(difference), difference,
                pctOf(buyerShare, difference));
        return new Quote(landPrice, planTotal, planTotal.subtract(landPrice), start, terms.minimumContribution(),
                List.of(exit(start, landPrice, terms), exit(half, landPrice, terms)),
                new Gain(terms.developerGainSharePct(), buyerShare, pctOf(buyerShare, MILLION), example),
                terms.inactivityMonths(), terms.noticeDays(), terms.maxMonths());
    }

    /**
     * What leaving after paying {@code paid} returns. The developer keeps the
     * deduction on what was paid, or a fixed sum on the land price taken from
     * the first payments — rounded down to the kobo, the escrow's own rule
     * (payments' {@code PayoutTranches.shares}), so the buyer is never quoted
     * less than escrow returns.
     */
    public static Exit exit(BigDecimal paid, BigDecimal landPrice, Terms terms) {
        BigDecimal keeps = terms.exitAppliesTo() == RefundAppliesTo.TOTAL_PRICE
                ? paid.min(pctOf(terms.exitDeductionPct(), landPrice))
                : pctOf(terms.exitDeductionPct(), paid);
        return new Exit(paid, paid.subtract(keeps), keeps);
    }

    private static BigDecimal pctOf(BigDecimal pct, BigDecimal amount) {
        return amount.multiply(pct).divide(HUNDRED, KOBO, RoundingMode.DOWN);
    }
}
