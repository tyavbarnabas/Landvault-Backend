package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.payments.InactivityPauses.Pause;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResaleInactivityPausesTest {

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    /** A hold during the listing's own pause counts once; separate pauses stay separate; a running one absorbs. */
    @Test
    void overlappingPausesBecomeOne() {
        assertThat(ResaleInactivityPauses.merged(List.of(new Pause(d(1), d(20)), new Pause(d(5), d(10)))))
                .containsExactly(new Pause(d(1), d(20)));
        assertThat(ResaleInactivityPauses.merged(List.of(new Pause(d(12), d(25)), new Pause(d(1), d(15)))))
                .containsExactly(new Pause(d(1), d(25)));
        assertThat(ResaleInactivityPauses.merged(List.of(new Pause(d(1), d(5)), new Pause(d(10), d(12)))))
                .containsExactly(new Pause(d(1), d(5)), new Pause(d(10), d(12)));
        assertThat(ResaleInactivityPauses.merged(List.of(new Pause(d(1), null), new Pause(d(3), d(4)))))
                .containsExactly(new Pause(d(1), null));
    }
}
