package com.techcomfort.landvaultbackend.payments;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sell my plot: on a flexible plan, a plot's first listing stops the
 * inactivity clock while it is up (D24). Declared here, implemented by the
 * resale module — the payments module works out when a plan has gone quiet,
 * and the resale module (which will need payments for payouts) knows when a
 * plot was listed. The same inverted interface as {@code ReceiptLookup}.
 */
public interface InactivityPauses {

    /** Each purchase's pauses, oldest first; a purchase with none is absent. */
    Map<UUID, List<Pause>> pausesOf(Collection<UUID> transactionIds);

    /**
     * Days, Lagos, from {@code from} up to but not including {@code until};
     * {@code until} is null while the pause is still running.
     */
    record Pause(LocalDate from, LocalDate until) {
    }
}
