package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/admin/tenants} body — Stage 1 onboarding, matching the
 * frontend's {@code CreateTenantDraftInput} exactly: company identity,
 * presence, plan, and the primary contact who becomes the tenant's first
 * Executive Director account (see AGENTS.md and
 * {@code TenantStaffAccountRequested}). {@code plan} is the enum's wire
 * value, validated/parsed in the service layer (an unknown value throws
 * {@code IllegalArgumentException}, translated the same as any other
 * malformed body).
 */
public record CreateTenantRequest(
        @NotNull @Valid CompanyIdentityDto identity,
        @NotNull @Valid PrimaryContactDto primaryContact,
        @NotNull @Valid CompanyPresenceDto presence,
        @NotBlank String plan
) {
}
