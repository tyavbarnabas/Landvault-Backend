package com.techcomfort.landvaultbackend.payments.internal.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TR-2's comparison: does the name the bank holds look like the company's? A
 * reviewer WARNING, never a block — trading names, abbreviations and
 * truncated bank names differ legitimately. Deliberately simple: upper-case,
 * punctuation removed, company-form words ("LTD", "LIMITED", …) ignored.
 */
final class SettlementAccountNames {

    private static final Set<String> IGNORED = Set.of("LTD", "LIMITED", "PLC", "CO", "COMPANY", "INC", "THE",
            "NIG", "NIGERIA");

    private SettlementAccountNames() {
    }

    /** True when the bank's name matches either of the company's names once normalised. */
    static boolean matchesCompany(String bankAccountName, String registeredName, String tradingName) {
        String bank = normalise(bankAccountName);
        if (bank.isEmpty()) {
            return false;
        }
        return bank.equals(normalise(registeredName)) || bank.equals(normalise(tradingName));
    }

    static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toUpperCase(Locale.ROOT).replace("&", " AND ").replaceAll("[^A-Z0-9]+", " ");
        return Arrays.stream(cleaned.trim().split(" "))
                .filter(word -> !word.isEmpty() && !IGNORED.contains(word))
                .collect(Collectors.joining(" "));
    }
}
