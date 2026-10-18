package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.documents.internal.render.LetterPlaceholders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a letter template may say. The same money and bank-detail rules as an
 * estate's conditions ({@link ClauseRules}), plus: only HomeTrue's fill-in
 * fields, and an opening that names the plot and the estate. Problems are keyed
 * by field ({@code opening}, {@code closing}, {@code signOff},
 * {@code standardClauses[i]}), all at once.
 */
final class TemplateRules {

    static final int MAX_OPENING = 1500;
    static final int MAX_CLOSING = 1000;
    static final int MAX_SIGN_OFF = 80;

    private TemplateRules() {
    }

    static Map<String, String> problems(String opening, String closing, String signOff, List<String> standardClauses) {
        Map<String, String> problems = new LinkedHashMap<>();
        paragraph(problems, "opening", opening, MAX_OPENING, true);
        if (closing != null && !closing.isBlank()) {
            paragraph(problems, "closing", closing, MAX_CLOSING, false);
        }
        if (signOff == null || signOff.isBlank()) {
            problems.put("signOff", "Write the sign-off line, for example \"Congratulations.\"");
        } else if (signOff.strip().length() > MAX_SIGN_OFF) {
            problems.put("signOff", "Keep the sign-off under " + MAX_SIGN_OFF + " characters.");
        } else if (signOff.contains("{")) {
            problems.put("signOff", "The sign-off can't have fill-in fields.");
        } else {
            money(problems, "signOff", signOff);
        }
        problems.putAll(ClauseRules.problems(standardClauses, "standardClauses"));
        return problems;
    }

    private static void paragraph(Map<String, String> problems, String field, String text, int max, boolean opening) {
        if (text == null || text.isBlank()) {
            problems.put(field, "Write the opening paragraph.");
            return;
        }
        if (text.strip().length() > max) {
            problems.put(field, "Keep it under " + max + " characters.");
            return;
        }
        Set<String> unknown = LetterPlaceholders.unknown(text);
        if (!unknown.isEmpty()) {
            problems.put(field, String.join(", ", unknown) + (unknown.size() == 1 ? " isn't" : " aren't")
                    + " a fill-in field. Use only: {" + String.join("}, {", LetterPlaceholders.FIELDS) + "}.");
            return;
        }
        if (opening && !LetterPlaceholders.REQUIRED_IN_OPENING.stream().allMatch(f -> LetterPlaceholders.uses(text, f))) {
            problems.put(field, "The opening must name what is allocated: include {plot} and {estate}.");
            return;
        }
        money(problems, field, LetterPlaceholders.withoutFields(text));
    }

    private static void money(Map<String, String> problems, String field, String text) {
        if (ClauseRules.hasBankDetails(text)) {
            problems.put(field, ClauseRules.BANK_MESSAGE);
        } else if (ClauseRules.hasMoney(text)) {
            problems.put(field, ClauseRules.MONEY_MESSAGE);
        }
    }
}
