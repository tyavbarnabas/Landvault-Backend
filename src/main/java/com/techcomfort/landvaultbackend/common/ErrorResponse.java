package com.techcomfort.landvaultbackend.common;

import java.util.Map;

/**
 * The platform-wide error body shape (AGENTS.md's "Response conventions to
 * match") — every controller's error responses use this, not a raw
 * exception message or Spring's default validation-error shape.
 */
public record ErrorResponse(String message, String code, Map<String, String> fieldErrors) {

    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse(message, code, null);
    }

    public static ErrorResponse fieldErrors(Map<String, String> fieldErrors) {
        return new ErrorResponse(null, "VALIDATION_ERROR", fieldErrors);
    }
}
