package com.techcomfort.landvaultbackend.identity.dto;

/** {@code register}/{@code login} response. */
public record AuthResponse(
        AuthUserResponse user,
        String token,
        String refreshToken
) {
}
