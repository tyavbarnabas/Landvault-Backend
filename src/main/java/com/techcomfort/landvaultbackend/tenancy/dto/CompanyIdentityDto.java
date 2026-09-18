package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * {@code companyType} is the enum's wire value, not the internal type — see
 * AGENTS.md. {@code statesOfOperation} has no dedicated source column;
 * derived from {@code registeredState}/{@code operatingState} — see
 * AGENTS.md's findings note, this is an approximation of the frontend's
 * actual concept, not a stored fact.
 * <p>
 * Doubles as {@code POST /api/admin/tenants}'s request shape (the
 * {@code @Valid}/{@code @NotBlank} annotations below only apply when bound
 * from a request body — they're inert on a response). When sent in a
 * request, {@code statesOfOperation} is accepted but not stored, same gap
 * as above.
 */
public record CompanyIdentityDto(
        @NotBlank String registeredName,
        String tradingName,
        @NotBlank String rcNumber,
        @NotBlank String companyType,
        @NotBlank String dateOfIncorporation,
        @NotNull @Valid AddressDto registeredAddress,
        @NotNull @Valid AddressDto operatingAddress,
        List<String> statesOfOperation
) {
}
