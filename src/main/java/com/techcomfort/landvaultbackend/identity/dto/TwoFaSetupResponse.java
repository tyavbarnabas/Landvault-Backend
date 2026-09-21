package com.techcomfort.landvaultbackend.identity.dto;

/**
 * {@code POST /api/auth/2fa/setup} response. 2FA is <strong>not</strong>
 * enabled at this point — the user must prove their app holds the secret via
 * {@code /2fa/confirm} first. See AGENTS.md.
 * <p>
 * {@code secret} is returned so a client can offer manual entry when a QR
 * code can't be scanned; it is the same value encoded in {@code otpAuthUri}.
 * This is the only response that ever carries it.
 */
public record TwoFaSetupResponse(String secret, String otpAuthUri) {
}
