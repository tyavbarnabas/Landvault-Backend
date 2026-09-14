package com.techcomfort.landvaultbackend.identity.internal.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashingTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void hashesNeverEqualTheRawPasswordAndVerifyCorrectly() {
        String hash = encoder.encode("correct horse battery staple");

        assertThat(hash).isNotEqualTo("correct horse battery staple");
        assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
        assertThat(encoder.matches("wrong password", hash)).isFalse();
    }

    @Test
    void sameRawPasswordHashesDifferentlyEachTime() {
        // BCrypt salts per-hash — this is what stops a rainbow-table
        // lookup even if two users share a password.
        String hashA = encoder.encode("shared-password");
        String hashB = encoder.encode("shared-password");

        assertThat(hashA).isNotEqualTo(hashB);
        assertThat(encoder.matches("shared-password", hashA)).isTrue();
        assertThat(encoder.matches("shared-password", hashB)).isTrue();
    }
}
