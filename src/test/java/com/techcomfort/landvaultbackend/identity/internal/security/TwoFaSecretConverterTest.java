package com.techcomfort.landvaultbackend.identity.internal.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TF-7: the stored form of a TOTP secret must never be the secret. */
class TwoFaSecretConverterTest {

    private static final String VALID_KEY = "bGFuZHZhdWx0LXRlc3Qtb25seS1rZXktMzJieXRlcyE=";
    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static TwoFaSecretConverter converterWith(String key) {
        TwoFaSecretConverter converter = new TwoFaSecretConverter(
                new TwoFaProperties(key, 1, 5, Duration.ofMinutes(15), Duration.ofMinutes(5), 10));
        converter.init();
        return converter;
    }

    @Test
    void roundTripsThroughEncryption() {
        TwoFaSecretConverter converter = converterWith(VALID_KEY);

        String stored = converter.convertToDatabaseColumn(SECRET);

        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(SECRET);
    }

    @Test
    void theStoredValueIsNotThePlaintextSecret() {
        TwoFaSecretConverter converter = converterWith(VALID_KEY);

        String stored = converter.convertToDatabaseColumn(SECRET);

        assertThat(stored)
                .as("anyone with database access must not be able to read the secret")
                .isNotEqualTo(SECRET)
                .doesNotContain(SECRET);
    }

    // A fixed IV would make identical secrets produce identical ciphertext,
    // leaking which accounts share a secret and weakening GCM badly.
    @Test
    void encryptingTheSameSecretTwiceProducesDifferentCiphertext() {
        TwoFaSecretConverter converter = converterWith(VALID_KEY);

        String first = converter.convertToDatabaseColumn(SECRET);
        String second = converter.convertToDatabaseColumn(SECRET);

        assertThat(first).isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(SECRET);
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(SECRET);
    }

    @Test
    void nullPassesThroughBothWays() {
        TwoFaSecretConverter converter = converterWith(VALID_KEY);

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    // GCM is authenticated: a tampered value must fail loudly rather than
    // decrypt to something plausible.
    @Test
    void aTamperedValueFailsRatherThanDecryptingToGarbage() {
        TwoFaSecretConverter converter = converterWith(VALID_KEY);
        String stored = converter.convertToDatabaseColumn(SECRET);
        String tampered = ('A' == stored.charAt(stored.length() - 2) ? "B" : "A")
                .transform(replacement -> stored.substring(0, stored.length() - 2) + replacement + stored.charAt(stored.length() - 1));

        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSecretEncryptedUnderOneKeyCannotBeReadWithAnother() {
        String stored = converterWith(VALID_KEY).convertToDatabaseColumn(SECRET);
        TwoFaSecretConverter otherKey = converterWith("YW5vdGhlci10ZXN0LWtleS1leGFjdGx5LTMyYnl0ZXM=");

        assertThatThrownBy(() -> otherKey.convertToEntityAttribute(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    // Same fail-loud-on-missing-config rule as JWT_SECRET — a blank or
    // wrong-sized key must stop startup, not silently weaken storage.
    @Test
    void refusesToStartWithoutAUsableKey() {
        assertThatThrownBy(() -> converterWith(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOTP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> converterWith("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOTP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> converterWith("dG9vLXNob3J0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> converterWith("not valid base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }
}
