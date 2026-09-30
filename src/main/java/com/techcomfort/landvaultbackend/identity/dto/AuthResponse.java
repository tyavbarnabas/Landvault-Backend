package com.techcomfort.landvaultbackend.identity.dto;

/**
 * {@code register}/{@code login}/{@code 2fa/verify} response. The refresh
 * token is deliberately absent: it travels only in an {@code HttpOnly}
 * cookie, and putting it here too would let any script read it.
 */
public record AuthResponse(
        AuthUserResponse user,
        String token
) {
}
