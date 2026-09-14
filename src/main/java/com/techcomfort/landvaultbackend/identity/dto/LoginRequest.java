package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/login} body — credential step only, OTP is a TODO. */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
