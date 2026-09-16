package com.techcomfort.landvaultbackend.tenancy.dto;

import java.util.List;

/**
 * {@code companyType} is the enum's wire value, not the internal type — see
 * AGENTS.md. {@code statesOfOperation} has no dedicated source column;
 * derived from {@code registeredState}/{@code operatingState} — see
 * AGENTS.md's findings note, this is an approximation of the frontend's
 * actual concept, not a stored fact.
 */
public record CompanyIdentityDto(
        String registeredName,
        String tradingName,
        String rcNumber,
        String companyType,
        String dateOfIncorporation,
        AddressDto registeredAddress,
        AddressDto operatingAddress,
        List<String> statesOfOperation
) {
}
