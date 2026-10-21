package com.techcomfort.landvaultbackend.identity.internal.exceptions;

/** Refusals when a buyer saves their address, handled by {@link AddressExceptionHandler}. */
public abstract class AddressException extends RuntimeException {

    private final String field;

    protected AddressException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }

    public static class UnknownCountry extends AddressException {
        public UnknownCountry(String code) {
            super("country", "\"" + code + "\" isn't a country code we recognise. Use a two-letter code, e.g. NG.");
        }
    }

    public static class UnknownState extends AddressException {
        public UnknownState(String state) {
            super("state", "\"" + state + "\" isn't a Nigerian state. Use one of the 36 states or the FCT.");
        }
    }
}
