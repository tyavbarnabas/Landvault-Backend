package com.techcomfort.landvaultbackend.tenancy.internal.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingDaysTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    // 2026-10-12 is a Monday.
    private static Instant lagos(int day, int hour) {
        return ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, LAGOS).toInstant();
    }

    @Test
    void midweekIsTwoCalendarDays() {
        assertThat(WorkingDays.before(lagos(15, 10), 2, LAGOS)).isEqualTo(lagos(13, 10));
    }

    @Test
    void theWeekendIsSkipped() {
        // Monday 10:00 → Thursday 10:00; Tuesday 10:00 → Friday 10:00.
        assertThat(WorkingDays.before(lagos(19, 10), 2, LAGOS)).isEqualTo(lagos(15, 10));
        assertThat(WorkingDays.before(lagos(20, 10), 2, LAGOS)).isEqualTo(lagos(16, 10));
    }

    @Test
    void waitingOverAWeekendAddsNothing() {
        // Saturday and Sunday both count from Saturday 00:00: Thursday 00:00.
        assertThat(WorkingDays.before(lagos(17, 15), 2, LAGOS)).isEqualTo(lagos(15, 0));
        assertThat(WorkingDays.before(lagos(18, 23), 2, LAGOS)).isEqualTo(lagos(15, 0));
    }

    @Test
    void aWeekendRequestIsFlaggedAfterAFullMondayAndTuesday() {
        Instant saturdayRequest = lagos(17, 10);
        assertThat(saturdayRequest.isBefore(WorkingDays.before(lagos(20, 23), 2, LAGOS))).as("Tuesday night").isFalse();
        assertThat(saturdayRequest.isBefore(WorkingDays.before(lagos(21, 0), 2, LAGOS))).as("Wednesday").isTrue();
    }

    @Test
    void daysAreCountedInLagosTime() {
        // 23:30 UTC on Friday is 00:30 Saturday in Lagos (UTC+1): already the weekend.
        Instant fridayLateUtc = Instant.parse("2026-10-16T23:30:00Z");
        assertThat(WorkingDays.before(fridayLateUtc, 2, LAGOS)).isEqualTo(lagos(15, 0));
    }
}
