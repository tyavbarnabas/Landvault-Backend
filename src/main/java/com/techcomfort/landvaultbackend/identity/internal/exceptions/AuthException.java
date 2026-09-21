package com.techcomfort.landvaultbackend.identity.internal.exceptions;

import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.service.AuthService;

/**
 * Base for {@link AuthService}'s control-flow exceptions, nested rather
 * than four separate files — small, closely related, and only ever
 * thrown/caught within this one auth flow (AuthService throws them,
 * AuthExceptionHandler catches them).
 */
public abstract class AuthException extends RuntimeException {

    /** Case-insensitive email uniqueness violation on registration. */
    public static class EmailAlreadyRegistered extends AuthException {
    }

    /** Deliberately generic — the same exception whether the email is unknown or the password is wrong. */
    public static class InvalidCredentials extends AuthException {
    }

    /** SUSPENDED or DEACTIVATED — a distinct, non-generic message from bad credentials. */
    public static class AccountNotActive extends AuthException {

        private final UserStatus status;

        public AccountNotActive(UserStatus status) {
            this.status = status;
        }

        public UserStatus status() {
            return status;
        }
    }

    /**
     * The account itself is fine, but it's tenant staff and its tenant's
     * {@code TenantStatus} isn't {@code ACTIVE} — see
     * {@code TenancyApi.isTenantActive} and AGENTS.md's session-revocation
     * note (tenancy slice B2). Deliberately doesn't carry which
     * {@code TenantStatus} the tenant is actually in — {@code identity}
     * only ever gets a {@code boolean} back from {@code TenancyApi}, never
     * the internal enum, so there's nothing more specific to report.
     */
    public static class TenantNotActive extends AuthException {
    }

    /** Not found, expired, revoked, or lost a concurrent rotation race. */
    public static class InvalidRefreshToken extends AuthException {
    }

    /**
     * Deliberately one exception for every password-reset failure: no code
     * was ever requested, the code expired, it was already used, its attempt
     * limit is exhausted, the code is simply wrong, or the email matches no
     * account at all. Distinguishing them would tell an attacker which
     * addresses are registered and whether a reset is in flight — the same
     * reasoning that makes {@link InvalidCredentials} generic.
     */
    public static class InvalidOrExpiredResetCode extends AuthException {
    }

    /**
     * One exception for every rejected second factor — wrong TOTP code,
     * wrong recovery code, already-used recovery code. Same generic-by-design
     * reasoning as {@link InvalidCredentials}: distinguishing them tells an
     * attacker which kind of secret they're closer to.
     */
    public static class InvalidTwoFactorCode extends AuthException {
    }

    /** The pending-login handle is unknown, expired, or already exchanged. */
    public static class InvalidOrExpiredChallenge extends AuthException {
    }

    /** Too many failed second-factor attempts; verification is refused until the lockout passes. */
    public static class TwoFactorLockedOut extends AuthException {
    }

    /** {@code /2fa/confirm} called with no secret issued — {@code /2fa/setup} has to run first. */
    public static class TwoFactorSetupRequired extends AuthException {
    }

    /** Disable/regenerate called on an account that hasn't confirmed 2FA. */
    public static class TwoFactorNotEnabled extends AuthException {
    }

    /**
     * Platform staff cannot turn 2FA off — they hold the platform-scope RLS
     * bypass, the most sensitive credential in the system. Deliberately
     * distinct from a generic 403 so the response can say why.
     */
    public static class TwoFactorMandatory extends AuthException {
    }
}
