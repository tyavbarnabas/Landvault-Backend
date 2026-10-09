package com.techcomfort.landvaultbackend.payments.internal.paystack;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * PY-7: is this webhook really from Paystack? Paystack signs the raw body
 * with HMAC-SHA512 using our secret key and sends the hex in
 * {@code x-paystack-signature} (per Paystack's own code samples). Checked over
 * the exact bytes received — never a parsed-and-re-serialised copy, which
 * would change the signature.
 */
public final class PaystackSignature {

    private static final String ALGORITHM = "HmacSHA512";

    private PaystackSignature() {
    }

    public static boolean isValid(byte[] rawBody, String signatureHeader, String secretKey) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody, secretKey).getBytes(StandardCharsets.US_ASCII);
        byte[] presented = signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        // Constant-time: the comparison's timing never reveals how much of a guess was right.
        return MessageDigest.isEqual(expected, presented);
    }

    /** Lowercase hex HMAC-SHA512 of the body. Public so tests can sign the way Paystack does. */
    public static String sign(byte[] rawBody, String secretKey) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 is unavailable in this JVM", e);
        }
    }
}
