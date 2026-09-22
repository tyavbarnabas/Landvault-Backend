package com.techcomfort.landvaultbackend.conflicts.internal.exceptions;

/**
 * Control-flow exceptions for the conflict review surfaces, nested rather
 * than one file each — same convention as {@code AuthException},
 * {@code TenancyException} and {@code InventoryException}.
 */
public abstract class ConflictException extends RuntimeException {

    /** No conflict with that id. Also what a platform-scope read returns for one that was soft-deleted. */
    public static class ConflictNotFound extends ConflictException {
    }

    /** The estate isn't the caller's, or doesn't exist — deliberately not distinguished. */
    public static class EstateNotFound extends ConflictException {
    }

    /** A transition the state machine doesn't allow, or a decision with no reason. */
    public static class InvalidTransition extends ConflictException {

        private final String detail;

        public InvalidTransition(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }
}
