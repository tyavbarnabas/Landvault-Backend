package com.techcomfort.landvaultbackend.documents.internal.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClauseRulesTest {

    @Test
    void ordinaryConditionsPass() {
        assertThat(ClauseRules.problems(List.of(
                "That this land is to be used for the construction of a 4 Bedroom Terrace Duplex.",
                "That the building shall not cover more than 60% of the plot.",
                "That a setback of 5 m from the estate road is kept.",
                "That the plot measures 1,000 sqm and may not be subdivided.",
                "That complaints are made to the estate office on 08031234567.",
                "That the estate lies 5 km from the expressway.",
                "That the estate covers five thousand square metres of green space.",
                "That two plots may be joined with the company's consent."))).isEmpty();
    }

    @Test
    void anyWayOfWritingMoneyIsRefused() {
        for (String clause : List.of(
                "That an infrastructure fee of ₦3,500,000 is payable.",
                "That N500,000 is paid for setting out.",
                "That N500 is paid on application.",
                "That 3,500,000 is paid before building.",
                "That five hundred thousand naira is paid.",
                "That NGN 200000 is paid for supervision.",
                "That $2000 is paid by diaspora buyers.",
                "That 2 million is paid at allocation.",
                "That five million is paid before building.",
                "That a charge of two hundred thousand applies to setting out.",
                "That fifty-thousand is paid for supervision.",
                "That one and a half billion covers the road.",
                "That 500k is paid on allocation.")) {
            assertThat(ClauseRules.problems(List.of(clause))).as(clause)
                    .containsEntry("clauses[0]", ClauseRules.MONEY_MESSAGE);
        }
    }

    @Test
    void bankDetailsAreRefusedButAPhoneNumberIsNot() {
        for (String clause : List.of(
                "That payment is made to Zenith Bank 1012345678.",
                "That payment is made to 0123 456 789.",
                "That payment is made to 012-345-6789.",
                "That the account number is on the invoice.",
                "That all balances are payable into our bank account.")) {
            assertThat(ClauseRules.problems(List.of(clause))).as(clause)
                    .containsEntry("clauses[0]", ClauseRules.BANK_MESSAGE);
        }
        assertThat(ClauseRules.hasBankDetails("Call 08031234567 for help.")).isFalse();
    }

    @Test
    void everyProblemIsReportedByPosition() {
        assertThat(ClauseRules.problems(List.of("Fine.", "  ", "Pay ₦10,000.", "x".repeat(601))))
                .containsOnlyKeys("clauses[1]", "clauses[2]", "clauses[3]");
    }
}
