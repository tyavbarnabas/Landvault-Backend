package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;

/**
 * A completed sign-in: the body the client sees, plus the raw refresh token
 * the controller puts in a cookie. Not a wire shape — the raw token must
 * never reach a response body.
 */
public record IssuedSession(AuthResponse response, String refreshToken) {
}
