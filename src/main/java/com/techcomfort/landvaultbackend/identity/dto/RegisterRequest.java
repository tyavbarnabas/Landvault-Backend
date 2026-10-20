package com.techcomfort.landvaultbackend.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.techcomfort.landvaultbackend.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/register} body. Buyers only — see AGENTS.md.
 * <p>
 * {@code ignoreUnknown = false} overrides the app's normal lenient JSON
 * deserialization specifically here: a request body carrying an unexpected
 * field (e.g. {@code tenantId}, {@code role}) must fail loudly, not have
 * that field silently dropped — see the task notes on why tenant staff can
 * never self-register.
 * <p>
 * {@code phone} must be E.164 — a plus, the country code, then the national
 * digits without the domestic 0 and without spaces — and is stored as sent.
 * Accounts registered before this rule keep whatever they stored.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RegisterRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        @Schema(description = "E.164: a plus, the country code, then the national number without its leading 0 "
                + "and without spaces. Stored as sent.", example = "+2348030000000")
        @NotNull(message = PHONE_MESSAGE) @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) String phone,
        @NotBlank String password,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotNull Currency currency
) {

    public static final String PHONE_PATTERN = "^\\+[1-9]\\d{7,14}$";
    public static final String PHONE_MESSAGE = "Enter your number with its country code, e.g. +2348030000000.";
}
