package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;

/**
 * Phase 5b: a notice of revocation, then how it ended. Only {@link #NOTICE} is
 * open; the rest are history.
 */
public enum RevocationStatus {
    /** The buyer has been told, and has until the deadline to pay what is overdue. */
    NOTICE,
    /** The developer withdrew the notice. */
    CANCELLED,
    /** The buyer caught up: finance verified enough that the trigger is no longer met. */
    CLEARED,
    /** The buyer withdrew from the plan while the notice ran. */
    WITHDRAWN,
    /** The developer confirmed it after the deadline: the plan ended. */
    REVOKED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
