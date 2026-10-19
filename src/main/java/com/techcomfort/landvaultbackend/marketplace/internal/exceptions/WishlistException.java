package com.techcomfort.landvaultbackend.marketplace.internal.exceptions;

/** Control-flow exceptions for the wishlist, nested like {@code PortfolioException}. */
public abstract class WishlistException extends RuntimeException {

    protected WishlistException(String message) {
        super(message);
    }

    public static class TypeUnknown extends WishlistException {
        public TypeUnknown(String value) {
            super("'" + value + "' isn't a listing type. Use primary.");
        }
    }

    public static class TypeNotSupported extends WishlistException {
        public TypeNotSupported(String value) {
            super("Saving " + value + " listings isn't available yet.");
        }
    }

    /** Not on the marketplace now. Never says why: unpublished, suspended and non-existent look the same. */
    public static class ListingNotFound extends WishlistException {
        public ListingNotFound() {
            super("Listing not found.");
        }
    }

    public static class Full extends WishlistException {
        public Full(int max) {
            super("Your wishlist is full (" + max + " listings). Remove one to save another.");
        }
    }
}
