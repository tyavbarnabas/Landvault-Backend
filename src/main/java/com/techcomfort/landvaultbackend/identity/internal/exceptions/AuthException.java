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

    /** Not found, expired, revoked, or lost a concurrent rotation race. */
    public static class InvalidRefreshToken extends AuthException {
    }
}
