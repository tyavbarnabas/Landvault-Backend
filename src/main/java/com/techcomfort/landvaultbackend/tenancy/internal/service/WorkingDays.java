package com.techcomfort.landvaultbackend.tenancy.internal.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/** Monday to Friday counting. Public holidays are not known, so they count as working days. */
final class WorkingDays {

    private WorkingDays() {
    }

    /**
     * The instant {@code days} working days before {@code now}: anything created
     * earlier has waited at least that long, weekends not counted. A weekend
     * "now" counts from the start of that Saturday, so waiting over a weekend
     * adds nothing.
     */
    static Instant before(Instant now, int days, ZoneId zone) {
        ZonedDateTime at = now.atZone(zone);
        if (isWeekend(at)) {
            at = at.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        }
        int counted = 0;
        while (counted < days) {
            at = at.minusDays(1);
            if (!isWeekend(at)) {
                counted++;
            }
        }
        return at.toInstant();
    }

    private static boolean isWeekend(ZonedDateTime at) {
        DayOfWeek day = at.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
