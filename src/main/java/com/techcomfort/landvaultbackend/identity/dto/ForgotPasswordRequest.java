package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/forgot-password} body. */
public record ForgotPasswordRequest(@NotBlank @Email String email) {
}
