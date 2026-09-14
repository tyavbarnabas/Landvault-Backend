package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/refresh} body. */
public record RefreshRequest(
        @NotBlank String refreshToken
) {
}
