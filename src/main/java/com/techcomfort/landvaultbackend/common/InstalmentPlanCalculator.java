package com.techcomfort.landvaultbackend.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * What a declared instalment plan costs at a given price — the one place the
 * figures are worked out, so the listing, the offer and the schedule can
 * never disagree. Decided with the user: the premium applies to the whole
 * price, a percentage deposit is a share of that total, the instalments split
 * the rest evenly and the leftover kobo goes on the last one. Every amount is
 * whole kobo, so each can be charged as it stands. See AGENTS.md,
 * "Instalment plans".
 */
public final class InstalmentPlanCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int KOBO = 2;

    private InstalmentPlanCalculator() {
    }

    /** A plan's terms as declared: exactly one of {@code depositPct} and {@code depositAmount}. */
    public record Terms(BigDecimal depositPct, BigDecimal depositAmount, int instalmentCount,
                        PaymentFrequency frequency, BigDecimal premiumPct) {
    }

    /**
     * @param planTotal       what the buyer pays in all under the plan
     * @param premium         how much more that is than paying in full
     * @param instalment      each instalment but the last
     * @param lastInstalment  the last, carrying any leftover kobo
     */
    public record Quote(BigDecimal price, BigDecimal planTotal, BigDecimal premium, BigDecimal deposit,
                        int instalmentCount, BigDecimal instalment, BigDecimal lastInstalment,
                        PaymentFrequency frequency) {

        /** Each instalment in order, the last one adjusted. */
        public List<BigDecimal> instalments() {
            return IntStream.rangeClosed(1, instalmentCount)
                    .mapToObj(n -> n == instalmentCount ? lastInstalment : instalment)
                    .toList();
        }
    }

    /**
     * The plan at this price. Empty when a fixed deposit leaves nothing to pay
     * in instalments — that plan can't be offered at this price.
     */
    public static Optional<Quote> quote(BigDecimal price, Terms terms) {
        BigDecimal premiumPct = terms.premiumPct() == null ? BigDecimal.ZERO : terms.premiumPct();
        BigDecimal planTotal = price.add(price.multiply(premiumPct).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .setScale(KOBO, RoundingMode.HALF_UP);
        BigDecimal deposit = (terms.depositPct() != null
                ? planTotal.multiply(terms.depositPct()).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : terms.depositAmount()).setScale(KOBO, RoundingMode.HALF_UP);
        BigDecimal balance = planTotal.subtract(deposit);
        if (deposit.signum() <= 0 || balance.signum() <= 0) {
            return Optional.empty();
        }
        int count = terms.instalmentCount();
        BigDecimal instalment = balance.divide(BigDecimal.valueOf(count), KOBO, RoundingMode.DOWN);
        if (instalment.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal last = balance.subtract(instalment.multiply(BigDecimal.valueOf(count - 1L)));
        return Optional.of(new Quote(price.setScale(KOBO, RoundingMode.HALF_UP), planTotal,
                planTotal.subtract(price).setScale(KOBO, RoundingMode.HALF_UP), deposit, count, instalment, last,
                terms.frequency()));
    }

    /**
     * Each instalment's due date, counted from the offer date (decided with the
     * user): the 1st falls one period after it, and so on. Always counted from
     * the offer date itself, so a short month moves one date to its last day
     * without dragging the rest (31 January → 28 February → 31 March).
     */
    public static List<LocalDate> dueDates(LocalDate offerDate, PaymentFrequency frequency, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(n -> offerDate.plusMonths((long) n * frequency.months()))
                .toList();
    }

    /**
     * Phase 5c: {@code balance} spread over {@code count} instalments the way a
     * plan is — equal parts rounded down to the kobo, the leftover on the last.
     * Empty when the parts would come to nothing.
     */
    public static Optional<List<BigDecimal>> split(BigDecimal balance, int count) {
        BigDecimal part = balance.divide(BigDecimal.valueOf(count), KOBO, RoundingMode.DOWN);
        if (count < 1 || part.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal last = balance.subtract(part.multiply(BigDecimal.valueOf(count - 1L))).setScale(KOBO, RoundingMode.UNNECESSARY);
        return Optional.of(IntStream.rangeClosed(1, count).mapToObj(n -> n == count ? last : part).toList());
    }

    /**
     * Phase 5c: due dates starting on {@code first} and every period after,
     * always counted from {@code first} (the same month-end rule as
     * {@link #dueDates}).
     */
    public static List<LocalDate> dueDatesFrom(LocalDate first, PaymentFrequency frequency, int count) {
        return IntStream.range(0, count).mapToObj(n -> first.plusMonths((long) n * frequency.months())).toList();
    }

    /** A declared late-payment step on one instalment (decided with the user: a share of the overdue instalment). */
    public static BigDecimal penaltyOn(BigDecimal instalment, BigDecimal penaltyPct) {
        return instalment.multiply(penaltyPct).divide(HUNDRED, KOBO, RoundingMode.HALF_UP);
    }
}
