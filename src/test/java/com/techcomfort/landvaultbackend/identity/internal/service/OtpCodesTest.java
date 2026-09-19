package com.techcomfort.landvaultbackend.identity.internal.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Code generation, hashing and comparison — the three rules the reset flow rests on. */
class OtpCodesTest {

    @Test
    void generatesSixDigitCodes() {
        IntStream.range(0, 500).forEach(i -> assertThat(OtpCodes.generate()).matches("\\d{6}"));
    }

    // A zero-padded code must stay six characters — String.valueOf(int) would
    // silently produce "42" for 42, shrinking the keyspace for those values.
    @Test
    void padsShortValuesRatherThanShorteningTheCode() {
        Set<Integer> lengths = new HashSet<>();
        IntStream.range(0, 2000).forEach(i -> lengths.add(OtpCodes.generate().length()));
        assertThat(lengths).containsExactly(6);
    }

    @Test
    void generatesVariedCodes() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 500).forEach(i -> codes.add(OtpCodes.generate()));
        // 500 draws from a million values: a handful of collisions is normal,
        // anything clustered is a broken RNG.
        assertThat(codes).hasSizeGreaterThan(450);
    }

    /**
     * Guards the RNG choice itself, which the observable output can't prove:
     * {@code Math.random()}/{@code Random} are seeded predictably, so an
     * attacker who sees one code can derive later ones — that turns a
     * brute-force problem into a guessing one. This fails if someone swaps
     * the generator.
     */
    @Test
    void usesSecureRandomNotOrdinaryRandom() {
        boolean hasSecureRandomField = false;
        for (Field field : OtpCodes.class.getDeclaredFields()) {
            if (SecureRandom.class.equals(field.getType())) {
                hasSecureRandomField = true;
            }
            assertThat(field.getType()).isNotEqualTo(java.util.Random.class);
        }
        assertThat(hasSecureRandomField).isTrue();
    }

    @Test
    void hashVerifyRoundTrips() {
        String code = OtpCodes.generate();
        String hash = OtpCodes.hash(code);

        assertThat(OtpCodes.matches(code, hash)).isTrue();
    }

    @Test
    void aDifferentCodeDoesNotMatch() {
        String hash = OtpCodes.hash("123456");

        assertThat(OtpCodes.matches("123457", hash)).isFalse();
        assertThat(OtpCodes.matches("000000", hash)).isFalse();
    }

    @Test
    void hashIsDeterministicAndDoesNotContainTheCode() {
        String hash = OtpCodes.hash("123456");

        assertThat(hash).isEqualTo(OtpCodes.hash("123456"));
        assertThat(hash).doesNotContain("123456");
        // SHA-256 as hex.
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
