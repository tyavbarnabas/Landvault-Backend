package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;
import java.util.Set;

/**
 * Phase 5c: a buyer's request to restructure their plan, and how it ended.
 * {@link #REQUESTED} and {@link #PROPOSED} are open; one at a time per plan.
 */
public enum RestructureStatus {
    /** The buyer asked; the developer hasn't decided. */
    REQUESTED,
    /** The developer proposed new terms; the buyer has until {@code expires_on} to accept. */
    PROPOSED,
    /** The buyer accepted: the new schedule is in force. */
    ACCEPTED,
    /** The developer said no, with a reason. */
    REJECTED,
    /** The buyer turned the proposal down. */
    DECLINED,
    /** The buyer withdrew their request before the developer decided. */
    CANCELLED,
    /** The developer withdrew their proposal (to propose again, say). */
    WITHDRAWN,
    /** The proposal ran out unaccepted, or what is owed changed under it. */
    LAPSED;

    public static final Set<RestructureStatus> OPEN = Set.of(REQUESTED, PROPOSED);

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
