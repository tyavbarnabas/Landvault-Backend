package com.techcomfort.landvaultbackend.identity.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generating, hashing and comparing one-time codes. Separate from
 * {@code AuthService} only so these three rules are directly unit-testable
 * without standing up the service.
 */
final class OtpCodes {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CODE_BOUND = 1_000_000;

    private OtpCodes() {
    }

    /**
     * Six digits, zero-padded, from {@link SecureRandom} — never
     * {@code Math.random()}/{@code Random}, whose output is predictable from
     * previous values and would make a code guessable rather than merely
     * brute-forceable.
     */
    static String generate() {
        return String.format("%06d", SECURE_RANDOM.nextInt(CODE_BOUND));
    }

    /**
     * SHA-256, deliberately not BCrypt — see AGENTS.md. A code is
     * short-lived and attempt-limited, so BCrypt's slowness buys nothing
     * here and would only make verification slower.
     */
    static String hash(String rawCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison, so verification time never narrows the code. */
    static boolean matches(String rawCode, String storedHash) {
        return MessageDigest.isEqual(
                hash(rawCode).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
