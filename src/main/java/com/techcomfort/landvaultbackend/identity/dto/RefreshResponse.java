package com.techcomfort.landvaultbackend.identity.dto;

/** {@code refresh} response — no {@code user}, the client already has one. */
public record RefreshResponse(String token, String refreshToken) {
}
