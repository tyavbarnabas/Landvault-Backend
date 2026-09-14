package com.techcomfort.landvaultbackend.identity.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techcomfort.landvaultbackend.common.Currency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same guard as {@link UserDtoSerializationTest}, for the DTO that actually
 * crosses the wire on register/login — the highest-stakes place for a
 * credential field to leak.
 */
class AuthUserResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializedJsonExcludesCredentialFields() throws Exception {
        AuthUserResponse dto = new AuthUserResponse(
                "Emeka Okonkwo", "emeka@example.com", "+2348000000000", "NG", Currency.NGN,
                "unsubmitted", "local", true, "client", List.of("client.dashboard.view"));

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
