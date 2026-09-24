package com.techcomfort.landvaultbackend.checkout.internal.exceptions;

/** What the reservation and checkout endpoints refuse on, and why. */
public sealed class CheckoutException extends RuntimeException {

    protected CheckoutException(String message) {
        super(message);
    }

    /**
     * The plot could not be held. Deliberately one exception for "someone
     * else got it", "it is already sold" and "it does not exist": all three
     * are the same answer to the buyer, and distinguishing them would let a
     * caller probe which plot ids exist on estates they cannot see.
     */
    public static final class PlotNotAvailable extends CheckoutException {
        public PlotNotAvailable() {
            super("That plot is no longer available.");
        }
    }

    /** The estate stopped meeting the publication conditions (TX-4). */
    public static final class EstateNotAvailable extends CheckoutException {
        public EstateNotAvailable(String message) {
            super(message);
        }
    }

    /** The buyer has not completed identity verification (KY-1). */
    public static final class KycRequired extends CheckoutException {
        public KycRequired() {
            super("Identity verification must be completed before reserving a plot.");
        }
    }

    /** No such reservation for this buyer — also what another buyer's hold looks like. */
    public static final class ReservationNotFound extends CheckoutException {
        public ReservationNotFound() {
            super("Reservation not found.");
        }
    }

    /** The hold is no longer active: expired, released, or already converted. */
    public static final class ReservationNotActive extends CheckoutException {
        public ReservationNotActive(String message) {
            super(message);
        }
    }

    /** No such transaction for this buyer — also what another buyer's looks like. */
    public static final class TransactionNotFound extends CheckoutException {
        public TransactionNotFound() {
            super("Transaction not found.");
        }
    }

    /** A second transaction against one hold. */
    public static final class TransactionAlreadyExists extends CheckoutException {
        public TransactionAlreadyExists() {
            super("A transaction already exists for that reservation.");
        }
    }

    /** A plan and a month count that contradict each other. */
    public static final class InvalidPaymentPlan extends CheckoutException {
        public InvalidPaymentPlan(String message) {
            super(message);
        }
    }
}
