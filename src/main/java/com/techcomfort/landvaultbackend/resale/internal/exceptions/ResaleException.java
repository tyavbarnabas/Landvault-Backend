package com.techcomfort.landvaultbackend.resale.internal.exceptions;

public abstract class ResaleException extends RuntimeException {

    protected ResaleException(String message) {
        super(message);
    }

    /** Not one of the caller's purchases — the same answer whether it exists or not. */
    public static class PlotNotFound extends ResaleException {
        public PlotNotFound() {
            super("No such plot in your portfolio.");
        }
    }

    /** A price that can't be quoted: below the floor, finer than a kobo, or not the caller's to choose. */
    public static class InvalidPrice extends ResaleException {
        public InvalidPrice(String message) {
            super(message);
        }
    }

    /** A plot that can't be listed now; every reason is in the message. */
    public static class NotAllowed extends ResaleException {
        public NotAllowed(String message) {
            super(message);
        }
    }

    public static class AlreadyListed extends ResaleException {
        public AlreadyListed() {
            super("This plot is already listed. Change its price or take it down instead.");
        }
    }

    public static class ListingNotFound extends ResaleException {
        public ListingNotFound() {
            super("No such listing of yours.");
        }
    }

    public static class ListingNotLive extends ResaleException {
        public ListingNotLive(String status) {
            super("This listing is " + status + ", so it can't be changed. List the plot again instead.");
        }
    }

    /** Phase 4: consent is only given or refused while the listing waits for it. */
    public static class ConsentNotAwaited extends ResaleException {
        public ConsentNotAwaited(String status) {
            super("This listing isn't waiting for consent; it is " + status.replace('_', ' ') + ".");
        }
    }

    public static class ConsentRequestLapsed extends ResaleException {
        public ConsentRequestLapsed() {
            super("The 14 days to answer this request have passed, so it has lapsed. The seller can list the plot "
                    + "again to ask once more.");
        }
    }

    public static class ConsentRequiresCompanyWideScope extends ResaleException {
        public ConsentRequiresCompanyWideScope() {
            super("Consent to a resale is given company-wide. Clear the branch filter and try again.");
        }
    }
}
