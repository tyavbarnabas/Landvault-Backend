package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.PaymentFrequency;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * How a letter states when instalments fall due. A regular schedule — every
 * date on the same day of the month, one period apart — reads as a rule
 * ("on the 15th of each month, from 15 November 2026 to 15 April 2028"); any
 * other (a month-end date that moved, a restructured gap) lists every date,
 * so a letter never states a rule the schedule doesn't follow. The full list
 * is always in the buyer's account.
 */
public final class DueDates {

    private DueDates() {
    }

    /** "on …", to follow "fall due" or "due". */
    public static String phrase(List<LocalDate> dates, PaymentFrequency frequency) {
        if (dates.size() == 1) {
            return "on " + DocumentFormats.date(dates.getFirst());
        }
        LocalDate first = dates.getFirst();
        boolean regular = IntStream.range(0, dates.size()).allMatch(n ->
                dates.get(n).equals(first.plusMonths((long) n * frequency.months()))
                        && dates.get(n).getDayOfMonth() == first.getDayOfMonth());
        if (!regular) {
            return "on " + String.join(", ", dates.stream().map(DocumentFormats::date).toList());
        }
        return "on the " + ordinal(first.getDayOfMonth())
                + (frequency == PaymentFrequency.MONTHLY ? " of each month" : " of every third month")
                + ", from " + DocumentFormats.date(first) + " to " + DocumentFormats.date(dates.getLast());
    }

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st, 22nd, 23rd … 31st. */
    static String ordinal(int day) {
        if (day % 100 >= 11 && day % 100 <= 13) {
            return day + "th";
        }
        return day + switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
