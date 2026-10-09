package com.techcomfort.landvaultbackend.payments.internal.service;

import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The parts always add up exactly to the sale, and none is over the cap. */
class PayoutSplitTest {

    private static final long CAP = 10_000_000_00L;

    @Test
    void underTheCapIsOnePart() {
        assertThat(PayoutSplit.partCount(6_000_000_00L, CAP)).isEqualTo(1);
        assertThat(PayoutSplit.partKobo(6_000_000_00L, 1, 1)).isEqualTo(6_000_000_00L);
        assertThat(PayoutSplit.partCount(CAP, CAP)).as("exactly the cap still fits").isEqualTo(1);
    }

    @Test
    void fifteenMillionIsTwoEvenParts() {
        assertThat(PayoutSplit.partCount(15_000_000_00L, CAP)).isEqualTo(2);
        assertThat(PayoutSplit.partKobo(15_000_000_00L, 2, 1)).isEqualTo(7_500_000_00L);
        assertThat(PayoutSplit.partKobo(15_000_000_00L, 2, 2)).isEqualTo(7_500_000_00L);
    }

    @Test
    void thirtyMillionIsThreeAndThirtyOneIsFour() {
        assertThat(PayoutSplit.partCount(30_000_000_00L, CAP)).isEqualTo(3);
        assertThat(PayoutSplit.partKobo(30_000_000_00L, 3, 2)).isEqualTo(10_000_000_00L);
        assertThat(PayoutSplit.partCount(31_000_000_00L, CAP)).isEqualTo(4);
        assertThat(PayoutSplit.partKobo(31_000_000_00L, 4, 1)).isEqualTo(7_750_000_00L);
    }

    @Test
    void oddKoboGoesOnTheLastPartAndTheSumIsExact() {
        long total = 22_500_001_13L;
        int parts = PayoutSplit.partCount(total, CAP);
        long sum = LongStream.rangeClosed(1, parts).map(p -> PayoutSplit.partKobo(total, parts, (int) p)).sum();
        assertThat(parts).isEqualTo(3);
        assertThat(sum).isEqualTo(total);
        assertThat(LongStream.rangeClosed(1, parts).map(p -> PayoutSplit.partKobo(total, parts, (int) p)))
                .allMatch(kobo -> kobo <= CAP);
    }
}
