package com.techcomfort.landvaultbackend.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place a plot's price is worked out. Nothing stores a corner
 * plot's price, so every surface that shows one has to compute it — and
 * they all have to compute it the same way, which is why this is a single
 * shared method rather than a line in each mapper.
 * <p>
 * <strong>{@link BigDecimal} throughout, never {@code double}.</strong> A
 * ₦85,000,000 plot with a 7.5% corner premium is exact here and off by
 * fractions of a naira in binary floating point — small enough to look
 * right and wrong enough to fail reconciliation against a payment gateway
 * that did the arithmetic correctly.
 */
public final class PlotPricing {

    /** Matches {@code price_tiers.price} — numeric(19,4). */
    private static final int MONEY_SCALE = 4;

    /** A display comparison, not money: two decimals is what a UI renders. */
    private static final int PER_SQM_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PlotPricing() {
    }

    /**
     * {@code tierPrice × (1 + cornerPremiumPct/100)} for a corner plot, the
     * tier's own price otherwise.
     * <p>
     * A null or zero premium yields the tier price unchanged rather than
     * running the multiplication — not an optimisation, but so the returned
     * value is the tier's price <em>exactly</em>, with no rescaling that
     * could shift a trailing digit on a plot that has no premium at all.
     */
    public static BigDecimal price(BigDecimal tierPrice, boolean isCorner, BigDecimal cornerPremiumPct) {
        if (tierPrice == null) {
            return null;
        }
        if (!isCorner || cornerPremiumPct == null || cornerPremiumPct.signum() == 0) {
            return tierPrice;
        }
        return tierPrice
                .multiply(BigDecimal.ONE.add(cornerPremiumPct.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Price divided by nominal size — <strong>null, never zero, when there
     * is no size to divide by</strong>.
     * <p>
     * A {@code UNIT_TYPE} tier (an apartment) has no exclusive land area, so
     * it has no per-square-metre rate; returning 0 there would render as a
     * free plot, and substituting the actual surveyed area would quote a
     * rate against a figure the plot isn't priced by. Absent is the honest
     * answer — see AGENTS.md's no-fabricated-data rule.
     * <p>
     * Note the direction of the arithmetic: this is derived <em>from</em>
     * the price, never the other way round. Larger plots are routinely
     * discounted per square metre, so pricing off a rate would quietly
     * overcharge every large plot — see {@code PriceTier.price}.
     */
    public static BigDecimal pricePerSqm(BigDecimal price, BigDecimal nominalSizeSqm) {
        if (price == null || nominalSizeSqm == null || nominalSizeSqm.signum() <= 0) {
            return null;
        }
        return price.divide(nominalSizeSqm, PER_SQM_SCALE, RoundingMode.HALF_UP);
    }
}
