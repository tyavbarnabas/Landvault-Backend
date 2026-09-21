package com.techcomfort.landvaultbackend.identity.internal.security;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts {@code users.two_fa_secret} at rest. A TOTP secret in plaintext is
 * strictly worse than the {@code directors.id_number} exposure already flagged
 * in AGENTS.md: that leaks personal data, this directly yields the ability to
 * generate valid second factors for any account, which defeats the entire
 * feature.
 * <p>
 * A converter rather than encrypt/decrypt calls in the service layer, for the
 * same reason {@code @SQLRestriction} lives on the entity rather than in every
 * repository method: it makes writing plaintext structurally impossible rather
 * than merely discouraged.
 * <p>
 * AES-256-GCM with a random 12-byte IV per value, stored as
 * {@code base64(iv || ciphertext)}. GCM is authenticated, so tampering with a
 * stored value fails loudly instead of decrypting to garbage.
 * <p>
 * TODO: key rotation is not implemented. Re-keying today means decrypting
 * every secret with the old key and re-encrypting with the new one, offline —
 * there is no key id on the stored value to support two keys at once. A single
 * configured symmetric key is the accepted scope for now; see AGENTS.md.
 */
@Component
@Converter(autoApply = false)
@RequiredArgsConstructor
@EnableConfigurationProperties(TwoFaProperties.class)
public class TwoFaSecretConverter implements AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TwoFaProperties properties;

    private SecretKeySpec key;

    @PostConstruct
    void init() {
        String configured = properties.encryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "landvault.two-fa.encryption-key (TOTP_ENCRYPTION_KEY) is not set. Refusing to start "
                            + "rather than store TOTP secrets in a form anyone with database access could use. "
                            + "Generate one with `openssl rand -base64 32` — see .env.example.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "landvault.two-fa.encryption-key (TOTP_ENCRYPTION_KEY) is not valid base64. "
                            + "Generate one with `openssl rand -base64 32`.", e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "landvault.two-fa.encryption-key (TOTP_ENCRYPTION_KEY) must decode to exactly "
                            + KEY_LENGTH_BYTES + " bytes for AES-256, got " + decoded.length
                            + ". Generate one with `openssl rand -base64 32`.");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintextSecret) {
        if (plaintextSecret == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintextSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) {
            // Deliberately does not include the secret or the exception's own
            // message in anything that could reach a response.
            throw new IllegalStateException("Failed to encrypt the TOTP secret", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored);
            ByteBuffer buffer = ByteBuffer.wrap(raw);

            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt the TOTP secret", e);
        }
    }
}
