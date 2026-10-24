package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A regular schedule reads as a rule; anything else lists every date, so a letter never states a false rule. */
class DueDatesTest {

    @Test
    void aRegularMonthlyScheduleReadsAsARule() {
        assertThat(DueDates.phrase(InstalmentPlanCalculator.dueDatesFrom(LocalDate.of(2026, 11, 15),
                PaymentFrequency.MONTHLY, 18), PaymentFrequency.MONTHLY))
                .isEqualTo("on the 15th of each month, from 15 November 2026 to 15 April 2028");
        assertThat(DueDates.phrase(InstalmentPlanCalculator.dueDatesFrom(LocalDate.of(2027, 1, 1),
                PaymentFrequency.QUARTERLY, 4), PaymentFrequency.QUARTERLY))
                .isEqualTo("on the 1st of every third month, from 1 January 2027 to 1 October 2027");
    }

    /** Counted from the 31st, February's falls on the 28th: not one day of the month, so every date is listed. */
    @Test
    void aScheduleThatMovesAMonthEndDateListsEveryDate() {
        assertThat(DueDates.phrase(InstalmentPlanCalculator.dueDatesFrom(LocalDate.of(2027, 1, 31),
                PaymentFrequency.MONTHLY, 3), PaymentFrequency.MONTHLY))
                .isEqualTo("on 31 January 2027, 28 February 2027, 31 March 2027");
    }

    @Test
    void oneDateIsJustTheDate() {
        assertThat(DueDates.phrase(List.of(LocalDate.of(2026, 12, 1)), PaymentFrequency.MONTHLY))
                .isEqualTo("on 1 December 2026");
    }

    @Test
    void ordinals() {
        assertThat(List.of(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).stream().map(DueDates::ordinal).toList())
                .containsExactly("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st");
    }
}
