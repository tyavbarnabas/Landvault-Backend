package com.techcomfort.landvaultbackend.documents.internal.service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A document's verification code: 64 random bits as 16 upper-case hex
 * characters, stored without separators and printed in groups of four
 * ({@code 3F9A-1C77-B02E-55D1}). Never derived from the id or the number —
 * those are guessable or sequential.
 */
public final class VerificationCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern STORED = Pattern.compile("^[0-9A-F]{16}$");

    private VerificationCodes() {
    }

    public static String generate() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    /** What a person typed or a QR code carried, as stored; null when it can't be a code at all. */
    public static String normalise(String input) {
        if (input == null) {
            return null;
        }
        String code = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return STORED.matcher(code).matches() ? code : null;
    }

    public static String display(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12) + "-"
                + code.substring(12, 16);
    }
}
