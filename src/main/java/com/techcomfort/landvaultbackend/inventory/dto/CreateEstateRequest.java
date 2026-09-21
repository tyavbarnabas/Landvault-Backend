package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/portal/estates} body.
 * <p>
 * There is deliberately <strong>no {@code tenantId} field</strong>: an
 * estate belongs to the authenticated caller's tenant, resolved from
 * {@code TenantContext}. Accepting one would be a cross-tenant breach on the
 * first request that supplied someone else's. Same rule as everywhere else in
 * this codebase.
 * <p>
 * {@code branchId} is only required when the caller's own scope is
 * organization-wide (an Executive Director), and is validated as belonging to
 * their tenant via {@code TenancyApi}. A branch-scoped caller has theirs
 * resolved for them and any supplied value is ignored.
 * <p>
 * {@code footprint} is optional — an estate exists as a draft before its
 * boundary is surveyed, and absent means absent.
 */
public record CreateEstateRequest(
        @NotBlank String name,
        String description,
        String area,
        String city,
        String state,
        String address,
        @PositiveOrZero BigDecimal cornerPremiumPct,
        String intent,
        List<String> amenities,
        UUID branchId,
        @Valid GeoJsonPolygonDto footprint
) {
}
