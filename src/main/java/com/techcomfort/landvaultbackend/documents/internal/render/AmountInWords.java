package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * "Fifteen Million Naira Only" — the amount in words, as Nigerian receipts
 * print it beside the figure. Exact: the amount is rounded to the minor unit
 * once (it already is, for a payment) and the two parts are spelled separately.
 */
public final class AmountInWords {

    private static final String[] ONES = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
            "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy",
            "Eighty", "Ninety"};
    private static final String[] SCALES = {"", "Thousand", "Million", "Billion", "Trillion"};

    private AmountInWords() {
    }

    public static String of(BigDecimal amount, Currency currency) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("A receipt amount is never negative.");
        }
        BigDecimal exact = amount.setScale(2, RoundingMode.HALF_UP);
        long major = exact.longValue();
        int minor = exact.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String[] units = switch (currency) {
            case NGN -> new String[]{"Naira", "Kobo"};
            case USD -> new String[]{"US Dollars", "Cents"};
            case GBP -> new String[]{"Pounds Sterling", "Pence"};
            case EUR -> new String[]{"Euros", "Cents"};
        };
        String words = spell(major) + " " + units[0];
        if (minor > 0) {
            words += " and " + spell(minor) + " " + units[1];
        }
        return words + " Only";
    }

    static String spell(long number) {
        if (number == 0) {
            return ONES[0];
        }
        StringBuilder words = new StringBuilder();
        int scale = 0;
        while (number > 0) {
            int group = (int) (number % 1000);
            if (group > 0) {
                if (scale >= SCALES.length) {
                    throw new IllegalArgumentException("Amount too large to spell.");
                }
                String part = spellHundreds(group) + (scale > 0 ? " " + SCALES[scale] : "");
                words.insert(0, words.isEmpty() ? part : part + ", ");
            }
            number /= 1000;
            scale++;
        }
        return words.toString();
    }

    private static String spellHundreds(int n) {
        StringBuilder s = new StringBuilder();
        if (n >= 100) {
            s.append(ONES[n / 100]).append(" Hundred");
            n %= 100;
            if (n > 0) {
                s.append(" and ");
            }
        }
        if (n >= 20) {
            s.append(TENS[n / 10]);
            if (n % 10 > 0) {
                s.append('-').append(ONES[n % 10]);
            }
        } else if (n > 0) {
            s.append(ONES[n]);
        }
        return s.toString();
    }
}
