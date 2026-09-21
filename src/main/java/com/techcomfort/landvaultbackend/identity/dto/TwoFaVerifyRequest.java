package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/2fa/verify} body — the second step of a two-step
 * login. {@code code} may be a TOTP code or one of the user's single-use
 * recovery codes.
 */
public record TwoFaVerifyRequest(@NotBlank String challengeToken, @NotBlank String code) {
}
