package com.techcomfort.landvaultbackend.checkout;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Declared here, implemented by the payments module — the inverted-interface
 * pattern (see AGENTS.md): payments depends on checkout, so checkout calling
 * payments would be a cycle. Batched: a page of purchases is summarised in one
 * call. A purchase with no payment yet is simply absent from the map.
 */
public interface PaymentSummaryProvider {

    Map<UUID, PaymentSummary> summariesFor(Collection<UUID> transactionIds);

    /** 3b: every succeeded, unflagged payment on each purchase, oldest first — an instalment plan has many. */
    Map<UUID, List<PlanPayment>> succeededPaymentsFor(Collection<UUID> transactionIds);

    /**
     * Phase 4: where each plan stands today, keyed like {@code schedules}. The
     * caller passes the schedules it already holds (checkout can't be called
     * back from here without a cycle).
     */
    Map<UUID, PlanStanding> standingsFor(Map<UUID, PlanSchedule> schedules);

    /** What is coming back on each purchase that has money owed back; others are absent. */
    Map<UUID, PlanRefund> refundsFor(Collection<UUID> transactionIds);
}
