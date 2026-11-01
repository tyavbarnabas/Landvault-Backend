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

    /** Phase 5a: not on the marketplace, held by someone else, or no longer for sale — one answer. */
    public static class ListingNotAvailable extends ResaleException {
        public ListingNotAvailable() {
            super("This plot is no longer available.");
        }
    }

    /** D15: a buyer can't buy their own plot, under this account or another of theirs. */
    public static class CannotBuyOwnPlot extends ResaleException {
        public CannotBuyOwnPlot() {
            super("You can't buy a plot you are selling.");
        }
    }

    /** KY-1, as for any hold: verification gates buying. */
    public static class KycRequired extends ResaleException {
        public KycRequired() {
            super("Identity verification must be completed before reserving a plot.");
        }
    }

    /** D25: while a new buyer holds the plot, the seller can't change or withdraw the listing. */
    public static class ListingHeld extends ResaleException {
        public ListingHeld() {
            super("A buyer is holding this plot to buy it, so the listing can't be changed or taken down "
                    + "until that ends.");
        }
    }
}
