package com.techcomfort.landvaultbackend.tenancy.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a couple of representative enums through Jackson to guard the
 * codebase's JSON enum-mapping convention (see AGENTS.md):
 * {@code @JsonValue}/{@code @JsonCreator} on every enum, mapping the Java
 * constant to the frontend's exact lowercase wire string. Plain unit test —
 * no Spring context, no database — so it stays fast and catches any future
 * enum that forgets the convention.
 */
class EnumJsonMappingTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void verificationStateRoundTripsThroughItsWireValue() throws Exception {
        assertThat(mapper.writeValueAsString(VerificationState.UNDER_REVIEW))
                .isEqualTo("\"under_review\"");
        assertThat(mapper.writeValueAsString(VerificationState.DOCUMENTS_SUBMITTED))
                .isEqualTo("\"documents_submitted\"");

        assertThat(mapper.readValue("\"under_review\"", VerificationState.class))
                .isEqualTo(VerificationState.UNDER_REVIEW);
        assertThat(mapper.readValue("\"documents_submitted\"", VerificationState.class))
                .isEqualTo(VerificationState.DOCUMENTS_SUBMITTED);
    }

    @Test
    void verificationDecisionTypeRoundTripsThroughItsWireValue() throws Exception {
        assertThat(mapper.writeValueAsString(VerificationDecisionType.REQUEST_MORE_INFO))
                .isEqualTo("\"request_more_info\"");

        assertThat(mapper.readValue("\"request_more_info\"", VerificationDecisionType.class))
                .isEqualTo(VerificationDecisionType.REQUEST_MORE_INFO);
        assertThat(mapper.readValue("\"approved\"", VerificationDecisionType.class))
                .isEqualTo(VerificationDecisionType.APPROVED);
    }

    @Test
    void rejectsAnUnmappedValue() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mapper.readValue("\"not_a_real_state\"", VerificationState.class));
    }
}
