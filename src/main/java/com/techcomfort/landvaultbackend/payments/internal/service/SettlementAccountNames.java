package com.techcomfort.landvaultbackend.payments.internal.service;

import java.util.Arrays;
import java.util.List;
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

    /**
     * For a person (a refund to a buyer): every word of their registered name
     * appears in the bank's name, in any order — banks list names in varying
     * orders and add middle names. A warning only, like the company check.
     */
    static boolean matchesPerson(String bankAccountName, String fullName) {
        Set<String> bank = Set.of(normalise(bankAccountName).split(" "));
        List<String> person = Arrays.stream(normalise(fullName).split(" ")).filter(w -> w.length() > 1).toList();
        return !person.isEmpty() && bank.containsAll(person);
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
