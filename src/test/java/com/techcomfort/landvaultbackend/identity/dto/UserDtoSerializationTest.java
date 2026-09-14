package com.techcomfort.landvaultbackend.identity.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techcomfort.landvaultbackend.common.Currency;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: {@link UserDto} must never carry credential material.
 * The fields simply not existing on the record already prevents this today
 * — this test is what catches someone adding one back later for
 * convenience. Plain Jackson, no Spring context.
 */
class UserDtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializedJsonExcludesCredentialFields() throws Exception {
        UserDto dto = new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Emeka", "Okonkwo", "emeka@example.com", "+2348000000000",
                "NG", Currency.NGN, "active", Instant.now());

        String json = mapper.writeValueAsString(dto);

        assertThat(json)
                .doesNotContain("passwordHash")
                .doesNotContain("password_hash")
                .doesNotContain("twoFaSecret")
                .doesNotContain("two_fa_secret")
                .doesNotContain("refreshToken")
                .doesNotContain("tokenHash")
                .doesNotContain("token_hash");
    }
}
