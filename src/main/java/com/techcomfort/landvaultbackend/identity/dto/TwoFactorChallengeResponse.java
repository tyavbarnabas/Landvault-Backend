package com.techcomfort.landvaultbackend.identity.dto;

import java.time.Instant;

/**
 * What {@code POST /api/auth/login} returns instead of tokens when the
 * account has 2FA enabled.
 * <p>
 * Deliberately shares no field name with {@link AuthResponse} — no
 * {@code token}, no {@code refreshToken}, no {@code user} — so a client
 * cannot mistake it for a successful login, and {@code twoFactorRequired}
 * states the case outright. The challenge carries no authority: it cannot
 * call a protected endpoint, it only identifies this pending login at
 * {@code /api/auth/2fa/verify}.
 */
public record TwoFactorChallengeResponse(boolean twoFactorRequired, String challengeToken, Instant expiresAt) {

    public static TwoFactorChallengeResponse of(String challengeToken, Instant expiresAt) {
        return new TwoFactorChallengeResponse(true, challengeToken, expiresAt);
    }
}
