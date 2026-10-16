package com.techcomfort.landvaultbackend.portfolio.internal.exceptions;

/** Control-flow exceptions for the portfolio, nested like {@code CheckoutException}. */
public abstract class PortfolioException extends RuntimeException {

    /** Not one of the caller's plots or pending purchases — someone else's is indistinguishable from none. */
    public static class PlotNotFound extends PortfolioException {
        @Override
        public String getMessage() {
            return "Plot not found in your portfolio.";
        }
    }

    public static class InvalidParameter extends PortfolioException {

        private final String detail;

        public InvalidParameter(String name, String detail) {
            this.detail = "'" + name + "' isn't a valid value. " + detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }
}
