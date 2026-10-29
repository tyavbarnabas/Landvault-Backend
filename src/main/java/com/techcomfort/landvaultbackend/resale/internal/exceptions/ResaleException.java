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
}
