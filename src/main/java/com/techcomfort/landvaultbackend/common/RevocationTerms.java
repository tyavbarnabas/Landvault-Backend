package com.techcomfort.landvaultbackend.common;

import java.time.LocalDate;

/**
 * Phase 5b: when an instalment plan may be revoked, as a number the buyer can
 * read and HomeTrue can check — never free text (decided with the user). An
 * instalment unpaid {@code afterMonths} whole months past its due date makes
 * the plan revocable; the buyer is then given {@code noticeDays} to pay what is
 * overdue. HomeTrue's floors apply to both. See AGENTS.md, "Instalment plans".
 */
public record RevocationTerms(int afterMonths, int noticeDays) {

    /** The least a developer may declare: an instalment three whole months unpaid. */
    public static final int MIN_AFTER_MONTHS = 3;

    public static final int MAX_AFTER_MONTHS = 120;

    /** The least notice a buyer is given, whatever the estate declared (decision 3). */
    public static final int MIN_NOTICE_DAYS = 14;

    public RevocationTerms {
        if (afterMonths < MIN_AFTER_MONTHS || noticeDays < MIN_NOTICE_DAYS) {
            throw new IllegalArgumentException("Revocation terms below HomeTrue's minimum: " + afterMonths
                    + " months, " + noticeDays + " days");
        }
    }

    /**
     * The terms a buyer is offered from what the estate declared: the notice
     * raised to HomeTrue's minimum. Null when nothing is declared.
     */
    public static RevocationTerms offered(Integer afterMonths, Integer declaredNoticeDays) {
        return afterMonths == null ? null
                : new RevocationTerms(afterMonths, Math.max(declaredNoticeDays == null ? 0 : declaredNoticeDays,
                MIN_NOTICE_DAYS));
    }

    /** Whether an instalment due on {@code dueDate} and still unpaid on {@code on} makes the plan revocable. */
    public boolean reachedBy(LocalDate dueDate, LocalDate on) {
        return LatePaymentTerms.monthsLate(dueDate, on) >= afterMonths;
    }

    /** The day an instalment due on {@code dueDate} makes the plan revocable if still unpaid. */
    public LocalDate revocableFrom(LocalDate dueDate) {
        return dueDate.plusMonths(afterMonths);
    }

    /** The last day to pay, for a notice given on {@code noticeDay}. */
    public LocalDate deadline(LocalDate noticeDay) {
        return noticeDay.plusDays(noticeDays);
    }
}
