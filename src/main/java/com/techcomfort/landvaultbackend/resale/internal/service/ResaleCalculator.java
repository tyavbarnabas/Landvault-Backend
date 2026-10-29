package com.techcomfort.landvaultbackend.resale.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Sell my plot: the rules as arithmetic, pure functions so they can be read
 * and tested on their own. The developer is always paid first. See AGENTS.md,
 * "Sell my plot".
 */
public final class ResaleCalculator {

    /** D4: on a plan, a profit needs this share of the agreed land price verified (its land share)... */
    public static final BigDecimal PROFIT_MIN_PAID_PCT = BigDecimal.valueOf(30);
    /** ...and this long since the first payment finance has verified, counted from the day it was paid. */
    public static final int PROFIT_MIN_MONTHS = 6;
    /** D9: a sale this far above the developer's current price waits for a Super Admin. */
    public static final BigDecimal REVIEW_ABOVE_PCT = BigDecimal.valueOf(50);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int KOBO = 2;

    private ResaleCalculator() {
    }

    /**
     * The land share of what was paid on a plan: each payment carries the
     * plan's premium in proportion, and premium already paid stays with the
     * developer — it paid for time the buyer had. Rounded down to the kobo.
     */
    public static BigDecimal landShare(BigDecimal paidInAll, BigDecimal agreedPrice, BigDecimal planTotal) {
        if (planTotal.signum() <= 0 || planTotal.compareTo(agreedPrice) <= 0) {
            return paidInAll;
        }
        return paidInAll.multiply(agreedPrice).divide(planTotal, KOBO, RoundingMode.DOWN);
    }

    /** The least that must be verified, rounded up to the kobo so 30% is never undershot. */
    public static BigDecimal minimumPaid(BigDecimal agreedPrice) {
        return agreedPrice.multiply(PROFIT_MIN_PAID_PCT).divide(HUNDRED, KOBO, RoundingMode.CEILING);
    }

    /** The day the time threshold is met: six months on from the first payment (as Paystack confirmed it). */
    public static LocalDate timeMetOn(LocalDate firstPaymentOn) {
        return firstPaymentOn.plusMonths(PROFIT_MIN_MONTHS);
    }

    public static boolean thresholdsMet(BigDecimal agreedPrice, BigDecimal paid, LocalDate firstPaymentOn,
                                        LocalDate today) {
        return paid.compareTo(minimumPaid(agreedPrice)) >= 0 && !today.isBefore(timeMetOn(firstPaymentOn));
    }

    /** D9: more than 50% above the developer's price. */
    public static boolean needsReview(BigDecimal price, BigDecimal developerPrice) {
        return developerPrice != null && price.compareTo(developerPrice.multiply(HUNDRED.add(REVIEW_ABOVE_PCT))
                .divide(HUNDRED, KOBO, RoundingMode.HALF_UP)) > 0;
    }

    /**
     * How a sale at {@code price} is split. The developer's balance is what is
     * still owed on the agreed land price (never the unearned premium) plus
     * penalties owed; then the developer's share of the gain — 100% on an early
     * sale (D19), the declared share on a plan, none when paid in full — with
     * the seller's side rounded down to the kobo, as gain-share claims are.
     *
     * @param paid               what finance verified towards the land price (penalties excluded)
     * @param developerGainPct   the developer's share of the gain, 0–100
     * @param transferFeePct     a declared transfer fee on the sale price, 0 when none
     */
    public static Split split(BigDecimal price, BigDecimal agreedPrice, BigDecimal paid, BigDecimal penaltiesOwed,
                              BigDecimal developerGainPct, BigDecimal transferFeePct) {
        BigDecimal balance = agreedPrice.subtract(paid).max(BigDecimal.ZERO).add(penaltiesOwed);
        BigDecimal gain = price.subtract(agreedPrice);
        BigDecimal sellerGain = gain.signum() <= 0 ? BigDecimal.ZERO
                : gain.multiply(HUNDRED.subtract(developerGainPct)).divide(HUNDRED, KOBO, RoundingMode.DOWN);
        BigDecimal developerGain = gain.signum() <= 0 ? BigDecimal.ZERO : gain.subtract(sellerGain);
        BigDecimal fee = price.multiply(transferFeePct).divide(HUNDRED, KOBO, RoundingMode.DOWN);
        BigDecimal developer = balance.add(developerGain).add(fee).min(price);
        return new Split(price, balance, penaltiesOwed, gain, developerGainPct, developerGain, fee, developer,
                price.subtract(developer));
    }

    /** @param gain the sale price less the agreed price; negative only on a paid-in-full sale below it */
    public record Split(BigDecimal price, BigDecimal developerBalance, BigDecimal penaltiesOwed, BigDecimal gain,
                        BigDecimal developerGainSharePct, BigDecimal developerGainShare, BigDecimal transferFee,
                        BigDecimal developerReceives, BigDecimal sellerReceives) {
    }
}
