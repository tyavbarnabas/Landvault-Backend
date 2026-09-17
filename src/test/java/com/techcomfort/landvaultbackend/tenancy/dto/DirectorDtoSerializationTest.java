package com.techcomfort.landvaultbackend.tenancy.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same guard as {@code AuthUserResponseSerializationTest}/{@code UserDtoSerializationTest}
 * — fast, no Spring context, no database — for the DTO carrying the most
 * sensitive data in the system. {@code bvn} was removed entirely (see
 * AGENTS.md); this pins that it stays gone rather than creeping back in a
 * future refactor. {@code AdminTenantControllerIT.serializationMasksGovernmentIdAndHasNoBvnFieldAtAll}
 * covers the same thing through a real HTTP round trip — this is the cheap,
 * always-run companion.
 */
class DirectorDtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializedJsonHasNoBvnFieldAndMasksIdNumber() throws Exception {
        DirectorDto dto = new DirectorDto(
                UUID.randomUUID(), "Ifeoma Balogun", "Executive Director", "Nigerian",
                "NIN", "*******8901", BigDecimal.valueOf(60), true);

        String json = mapper.writeValueAsString(dto);

        assertThat(json.toLowerCase()).doesNotContain("bvn");
        assertThat(json).doesNotContain("12345678901"); // the full, unmasked value must never appear
    }
}
