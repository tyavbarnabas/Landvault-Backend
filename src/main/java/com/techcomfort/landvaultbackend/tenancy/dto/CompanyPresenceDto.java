package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Doubles as {@code POST /api/admin/tenants}'s {@code presence} field — validation is inert on a response. */
public record CompanyPresenceDto(
        @NotBlank @Email String companyEmail,
        @NotBlank String companyPhone,
        String website,
        @NotNull @Valid SocialsDto socials
) {
}
