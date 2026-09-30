package com.techcomfort.landvaultbackend.identity.dto;

/**
 * {@code refresh} response: the same {@code { user, token }} shape as login,
 * so a page load can restore a whole session with one call. No refresh
 * token: that travels only in the {@code HttpOnly} cookie.
 */
public record RefreshResponse(AuthUserResponse user, String token) {
}
