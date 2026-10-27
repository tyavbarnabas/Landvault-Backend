package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;

/** Why a plan was given notice (changeset 113). */
public enum NoticeTrigger {
    /** An instalment plan: an instalment unpaid as long as the purchase's terms say. */
    ARREARS,
    /** A flexible plan: no payment for its declared inactivity period. A verified payment ends it. */
    INACTIVITY,
    /** A flexible plan: past its date to finish by. Only finishing the plan ends it. */
    FINISH_BY;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
