package com.techcomfort.landvaultbackend.identity.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.util.List;

/**
 * Matches the frontend's {@code AuthUser} shape exactly (authService.ts) —
 * not {@link UserDto}, which serves IdentityApi's cross-module contract
 * instead. {@code kycStatus}/{@code kycType} are computed defaults (no KYC
 * module exists yet) — "unsubmitted" is the honest state, not a fabrication.
 * Never carries passwordHash, the raw refresh token, or twoFaSecret.
 * <p>
 * {@code mustChangePassword} is true only for a bootstrapped Super Admin
 * account today (see {@code SuperAdminBootstrap}) — surfaced here so the
 * frontend can route to a change-password screen, but NOT yet enforced
 * server-side (that screen doesn't exist). See AGENTS.md.
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
        boolean mustChangePassword,
        /**
         * True for platform staff who haven't confirmed 2FA. 2FA is
         * mandatory for them (they hold the platform-scope RLS bypass), but
         * login is deliberately NOT blocked on it — doing so would strand
         * the bootstrapped Super Admin, who has no way to set it up without
         * signing in first. The frontend routes on this flag instead. See
         * AGENTS.md for the intended first-login order.
         */
        boolean mustSetUpTwoFa,
        /** 0 when 2FA is off. Lets the user see when it's time to regenerate. */
        long recoveryCodesRemaining,
        String role,
        List<String> permissions
) {
}
