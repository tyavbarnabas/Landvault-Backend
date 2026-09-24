package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/login} body — credential step only, OTP is a TODO. */
@Schema(
        name = "LoginRequest",
        example = """
                {
                  "email": "ada@estintingroup.com",
                  "password": "correct horse battery staple"
                }""")
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
