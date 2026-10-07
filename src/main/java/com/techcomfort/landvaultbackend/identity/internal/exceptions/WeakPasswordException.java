package com.techcomfort.landvaultbackend.identity.internal.exceptions;

/** A password that breaks {@code PasswordPolicy}: 400 WEAK_PASSWORD, reported against the request's field. Never carries the password. */
public class WeakPasswordException extends RuntimeException {

    private final String field;

    public WeakPasswordException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
