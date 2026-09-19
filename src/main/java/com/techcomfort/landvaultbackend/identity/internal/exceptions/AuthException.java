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
}
