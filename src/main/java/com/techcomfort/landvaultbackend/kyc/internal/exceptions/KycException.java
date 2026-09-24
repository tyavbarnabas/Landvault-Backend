package com.techcomfort.landvaultbackend.kyc.internal.exceptions;

/** What the KYC endpoints refuse on, and why. */
public sealed class KycException extends RuntimeException {

    protected KycException(String message) {
        super(message);
    }

    /** The submission didn't carry every document this buyer's country requires. */
    public static final class MissingDocuments extends KycException {
        public MissingDocuments(String message) {
            super(message);
        }
    }

    /** No verification record exists for that buyer, so there is nothing to decide on. */
    public static final class RecordNotFound extends KycException {
        public RecordNotFound() {
            super("No KYC submission found for that buyer.");
        }
    }

    /**
     * A decision that cannot be recorded — an already-decided record, a
     * rejection naming no failed document, or one naming a document this
     * buyer was never asked for.
     */
    public static final class InvalidDecision extends KycException {
        public InvalidDecision(String message) {
            super(message);
        }
    }

    /** The account behind the token no longer exists. */
    public static final class BuyerNotFound extends KycException {
        public BuyerNotFound() {
            super("Buyer not found.");
        }
    }
}
