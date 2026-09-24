package com.techcomfort.landvaultbackend.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM over a configured symmetric key, as {@code base64(iv || ciphertext)}.
 * <p>
 * Cross-cutting infrastructure rather than domain logic, which is why it
 * lives in {@code common}: two modules now encrypt a column at rest
 * ({@code users.two_fa_secret} and {@code kyc_records.nin_number}), and two
 * hand-rolled copies of the same cipher construction is precisely the shape
 * that leaves one of them quietly wrong — a reused IV, a missing tag length,
 * a swapped mode — with nothing failing until it matters.
 * <p>
 * GCM is authenticated, so a tampered value fails loudly instead of
 * decrypting to garbage. The IV is random per value, never reused.
 * <p>
 * <strong>Key rotation is not implemented</strong>, deliberately and
 * consistently with {@code TwoFaSecretConverter}: there is no key id on a
 * stored value, so two keys cannot coexist and re-keying means decrypting
 * everything with the old key and re-encrypting offline. Changing a key
 * without that migration makes every existing value undecryptable.
 * <p>
 * {@code TwoFaSecretConverter} predates this class and still carries its own
 * copy. It was left alone rather than refactored mid-slice: 2FA is shipped
 * and working, and rewriting its cipher to prove a tidiness point is not a
 * trade this slice needed to make. Converging the two is worth doing, in a
 * change whose own tests are about that and nothing else.
 */
public final class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private AesGcmCipher(SecretKeySpec key) {
        this.key = key;
    }

    /**
     * Fails fast and loudly on a missing, malformed or wrong-sized key — the
     * same rule as {@code JWT_SECRET} and {@code TOTP_ENCRYPTION_KEY}. An
     * application that starts without a usable key would write the very
     * plaintext the column exists to keep encrypted.
     *
     * @param propertyName named in every failure message so the operator is
     *                     told which setting to fix, not merely that one is
     *                     broken
     */
    public static AesGcmCipher fromBase64Key(String base64Key, String propertyName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " is not set. Refusing to start rather than store regulated personal "
                            + "data in a form anyone with database access could read. Generate one with "
                            + "`openssl rand -base64 32` — see .env.example.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    propertyName + " is not valid base64. Generate one with `openssl rand -base64 32`.", e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    propertyName + " must decode to exactly " + KEY_LENGTH_BYTES + " bytes for AES-256, got "
                            + decoded.length + ". Generate one with `openssl rand -base64 32`.");
        }
        return new AesGcmCipher(new SecretKeySpec(decoded, "AES"));
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) {
            // Neither the plaintext nor the underlying message is included:
            // this exception can reach a log, and the value it was handed is
            // the thing being protected.
            throw new IllegalStateException("Failed to encrypt a protected value", e);
        }
    }

    public String decrypt(String stored) {
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
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt a protected value", e);
        }
    }
}
