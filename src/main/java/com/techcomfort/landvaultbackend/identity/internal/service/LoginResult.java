package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.TwoFactorChallengeResponse;

/**
 * What {@code login()} produced: either a completed session, or a pending
 * one awaiting a second factor. Exactly one field is non-null.
 * <p>
 * Not a wire shape — the controller unwraps it and serialises whichever side
 * is populated, so the JSON a client sees is either {@link AuthResponse} or
 * {@link TwoFactorChallengeResponse}, never this.
 */
public record LoginResult(AuthResponse authResponse, TwoFactorChallengeResponse challenge) {

    static LoginResult completed(AuthResponse authResponse) {
        return new LoginResult(authResponse, null);
    }

    static LoginResult pendingTwoFactor(TwoFactorChallengeResponse challenge) {
        return new LoginResult(null, challenge);
    }

    public boolean requiresTwoFactor() {
        return challenge != null;
    }
}
