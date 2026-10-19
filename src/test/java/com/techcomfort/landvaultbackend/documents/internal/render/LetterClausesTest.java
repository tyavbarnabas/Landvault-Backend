package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LetterClausesTest {

    private static final EstateDocumentFacts NO_DISCLOSURE = new EstateDocumentFacts(UUID.randomUUID(),
            UUID.randomUUID(), null, "Lugbe Demo Gardens", "Lugbe", "Abuja", "Federal Capital Territory (Abuja)",
            null, null, null);

    @Test
    void aConditionRepeatedInTheTemplateAndTheEstatePrintsOnceWhereItFirstAppears() {
        List<String> clauses = LetterClauses.of(
                List.of("That the company must be notified before commencement of any work on site."),
                List.of("That this land is for a 4 Bedroom Terrace Duplex.",
                        "that the company must be  notified before commencement of any work on site"),
                NO_DISCLOSURE);

        assertThat(clauses).containsExactly(
                "That the company must be notified before commencement of any work on site.",
                "That this land is for a 4 Bedroom Terrace Duplex.",
                DisclosureClauses.PAY_ONLY_THROUGH_HOMETRUE);
    }

    @Test
    void conditionsThatOnlyLookAlikeAreKept() {
        List<String> clauses = LetterClauses.of(
                List.of("That you must commence work within three months."),
                List.of("That you must commence work within six months."),
                NO_DISCLOSURE);

        assertThat(clauses).hasSize(3);
    }
}
