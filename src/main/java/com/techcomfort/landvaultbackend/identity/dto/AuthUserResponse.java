package com.techcomfort.landvaultbackend.identity.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.util.List;

/**
 * Matches the frontend's {@code AuthUser} shape exactly (authService.ts) —
 * not {@link UserDto}, which serves IdentityApi's cross-module contract
 * instead. {@code kycStatus}/{@code kycType} are computed defaults (no KYC
 * module exists yet) — "unsubmitted" is the honest state, not a fabrication.
 * Never carries passwordHash, the raw refresh token, or twoFaSecret.
 */
public record AuthUserResponse(
        String name,
        String email,
        String phone,
        String country,
        Currency currency,
        String kycStatus,
        String kycType,
        boolean twoFAEnabled,
        String role,
        List<String> permissions
) {
}
