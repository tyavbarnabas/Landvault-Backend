package com.techcomfort.landvaultbackend.payments.internal.service;

/**
 * How a sale is divided into transfers under Paystack's per-transfer cap
 * (decided with the user): the fewest parts that fit, split EVENLY, with any
 * leftover kobo on the last part — so the parts always add up exactly to the
 * sale. ₦15M at a ₦10M cap → 2 × ₦7.5M; ₦30M → 3 × ₦10M; ₦31M → 4 × ₦7.75M.
 * Pure arithmetic on whole kobo; never floating point.
 */
final class PayoutSplit {

    private PayoutSplit() {
    }

    static int partCount(long totalKobo, long maxKoboPerTransfer) {
        if (totalKobo <= 0 || maxKoboPerTransfer <= 0) {
            throw new IllegalArgumentException("amounts must be positive");
        }
        return Math.toIntExact((totalKobo + maxKoboPerTransfer - 1) / maxKoboPerTransfer);
    }

    /** Part {@code partNumber} (1-based) of {@code partCount}. */
    static long partKobo(long totalKobo, int partCount, int partNumber) {
        if (partNumber < 1 || partNumber > partCount) {
            throw new IllegalArgumentException("part " + partNumber + " of " + partCount);
        }
        long even = totalKobo / partCount;
        return partNumber < partCount ? even : totalKobo - even * (partCount - 1);
    }
}
