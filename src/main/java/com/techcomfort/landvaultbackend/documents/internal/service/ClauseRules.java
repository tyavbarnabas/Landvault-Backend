package com.techcomfort.landvaultbackend.documents.internal.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a developer may not write into an allocation clause (DS-3, DS-4).
 * <ul>
 *   <li><b>Money.</b> Fees, deductions and charges come from the estate's cost
 *   disclosure and are added to the letter automatically, so a letter can never
 *   quote a figure the buyer wasn't shown before paying.</li>
 *   <li><b>Bank details.</b> Buyers pay only through HomeTrue; a letter naming
 *   an account is the off-platform payment the platform exists to stop.</li>
 * </ul>
 * Sums in words are caught too ("five million", "two hundred thousand", "500k").
 * Still a mistake-catcher, not a guarantee — determined rewording gets past any
 * pattern, which is why HomeTrue reviews every version before a letter may use
 * it (see {@code LetterTermsReviewService}). Sizes in square metres and
 * percentages are allowed, in figures or in words.
 */
public final class ClauseRules {

    public static final int MAX_CLAUSES = 30;
    public static final int MAX_LENGTH = 600;

    /** A currency mark or word, an "N" written straight before a figure (N500), or a figure in thousands or millions. */
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(₦|[$£€]|\\bNGN\\b|\\bUSD\\b|\\bGBP\\b|\\bEUR\\b|\\bnaira\\b|\\bkobo\\b|\\bN\\d"
                    + "|\\d\\s*(million|billion|thousand)\\b)");
    /** "million" or "billion" anywhere — no land condition needs them — and a "500k" shorthand. */
    private static final Pattern LARGE_SUM = Pattern.compile("(?i)\\b(million|billion|trillion)s?\\b|\\d\\s*k\\b");
    /** A number in words before "thousand" (two hundred thousand, fifty-thousand) — unless it is an area. */
    private static final Pattern THOUSANDS_IN_WORDS = Pattern.compile(
            "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen"
                    + "|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
                    + "|hundred|a)[\\s-]+thousands?\\b(?!\\s*(sq|m²|m2\\b|square|hectare|ha\\b|acre))");
    /** A figure with thousands separators (3,500,000) — money unless it is an area. */
    private static final Pattern GROUPED = Pattern.compile(
            "\\b\\d{1,3}(,\\d{3})+(\\.\\d+)?(?!\\s*(sq|m²|m2\\b|square|hectare|ha\\b|acre|%|percent))",
            Pattern.CASE_INSENSITIVE);
    /** Digits, allowing single spaces or dashes between them, so "0123 456 789" is one run. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d(?:[ -]?\\d)*");
    private static final Pattern BANK_WORDS = Pattern.compile(
            "(?i)\\b(account\\s*(number|no\\.?|name|details)|acct\\.?\\s*(no|number)|a/c\\b|bank\\s+details"
                    + "|sort\\s+code|pay(able)?\\s+(in)?to\\s+(our|the\\s+company'?s?)\\s+(bank|account))");

    static final String MONEY_MESSAGE = "Remove the amount. Fees and other figures come from this estate's "
            + "cost disclosure and are added to the letter automatically.";
    static final String BANK_MESSAGE = "Remove the bank details. Buyers pay only through HomeTrue, never into "
            + "an account named in a letter.";

    private ClauseRules() {
    }

    /** Problems keyed {@code clauses[i]}, in clause order; empty when every clause is acceptable. */
    public static Map<String, String> problems(List<String> clauses) {
        return problems(clauses, "clauses");
    }

    /** As {@link #problems(List)}, keyed {@code field[i]}. */
    public static Map<String, String> problems(List<String> clauses, String field) {
        Map<String, String> problems = new LinkedHashMap<>();
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i);
            if (clause == null || clause.isBlank()) {
                problems.put(key(field, i), "Write the clause, or remove it.");
            } else if (clause.strip().length() > MAX_LENGTH) {
                problems.put(key(field, i), "Keep each clause under " + MAX_LENGTH + " characters.");
            } else if (hasBankDetails(clause)) {
                problems.put(key(field, i), BANK_MESSAGE);
            } else if (hasMoney(clause)) {
                problems.put(key(field, i), MONEY_MESSAGE);
            }
        }
        return problems;
    }

    static boolean hasMoney(String clause) {
        return MONEY.matcher(clause).find() || GROUPED.matcher(clause).find()
                || LARGE_SUM.matcher(clause).find() || THOUSANDS_IN_WORDS.matcher(clause).find();
    }

    /** A NUBAN is ten digits; an eleven-digit phone number is not flagged. */
    static boolean hasBankDetails(String clause) {
        if (BANK_WORDS.matcher(clause).find()) {
            return true;
        }
        Matcher run = DIGIT_RUN.matcher(clause);
        while (run.find()) {
            if (run.group().replaceAll("[ -]", "").length() == 10) {
                return true;
            }
        }
        return false;
    }

    private static String key(String field, int i) {
        return field + "[" + i + "]";
    }
}
