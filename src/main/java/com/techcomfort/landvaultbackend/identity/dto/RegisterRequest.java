package com.techcomfort.landvaultbackend.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.techcomfort.landvaultbackend.common.Currency;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/register} body. Buyers only — see AGENTS.md.
 * <p>
 * {@code ignoreUnknown = false} overrides the app's normal lenient JSON
 * deserialization specifically here: a request body carrying an unexpected
 * field (e.g. {@code tenantId}, {@code role}) must fail loudly, not have
 * that field silently dropped — see the task notes on why tenant staff can
 * never self-register.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RegisterRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        @NotBlank String phone,
        @NotBlank String password,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotNull Currency currency
) {
}
