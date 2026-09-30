package com.techcomfort.landvaultbackend.identity.dto;

/**
 * {@code refresh} response — no {@code user}, the client already has one,
 * and no refresh token: that travels only in the {@code HttpOnly} cookie.
 */
public record RefreshResponse(String token) {
}
