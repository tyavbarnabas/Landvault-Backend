package com.techcomfort.landvaultbackend.payments.internal.enums;

/** AR-3: when an instalment reminder is sent. Each goes once per instalment (changeset 104). */
public enum ReminderStage {
    SEVEN_DAYS_BEFORE,
    ONE_DAY_BEFORE,
    /** The first day after the due date, inside the 7-day grace. */
    OVERDUE,
    /** Past the grace: in arrears. */
    IN_ARREARS
}
