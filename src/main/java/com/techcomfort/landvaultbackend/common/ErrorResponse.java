package com.techcomfort.landvaultbackend.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The platform-wide error body shape (AGENTS.md's "Response conventions to
 * match") — every controller's error responses use this, not a raw
 * exception message or Spring's default validation-error shape.
 */
@Schema(
        name = "ErrorResponse",
        description = """
                Every error in this API. The HTTP status carries the primary signal: **4xx is a \
                client error and must not be retried; 5xx is transient** and may be, with backoff, \
                for idempotent or idempotency-keyed requests.

                `code` is a stable machine-readable string — branch on it rather than on `message`, \
                which is written for people and may change. `fieldErrors` is populated only for \
                validation failures.""",
        example = """
                {
                  "message": "A reason is required when recording dismissed.",
                  "code": "INVALID_CONFLICT_TRANSITION",
                  "fieldErrors": null
                }""")
public record ErrorResponse(String message, String code, Map<String, String> fieldErrors) {

    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse(message, code, null);
    }

    public static ErrorResponse fieldErrors(Map<String, String> fieldErrors) {
        return new ErrorResponse(null, "VALIDATION_ERROR", fieldErrors);
    }
}
