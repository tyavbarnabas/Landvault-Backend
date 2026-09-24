package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
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
@Schema(
        name = "CreateEstateRequest",
        description = "A new estate. `tenantId` is never accepted here — it comes from your token.",
        example = """
                {
                  "name": "Gwarinpa Heights",
                  "description": "Phase 1",
                  "area": "Gwarinpa",
                  "city": "Abuja",
                  "state": "FCT",
                  "address": "1 Access Road",
                  "cornerPremiumPct": 12.50,
                  "intent": "development",
                  "amenities": ["24/7 security", "Borehole", "Paved roads"],
                  "branchId": "d94ecb29-be86-49b4-b7fa-a478a948d3da",
                  "footprint": {
                    "type": "Polygon",
                    "coordinates": [[
                      [7.400, 9.100], [7.409, 9.100], [7.409, 9.109], [7.400, 9.109], [7.400, 9.100]
                    ]]
                  }
                }""")
public record CreateEstateRequest(
        @NotBlank String name,
        String description,
        String area,
        String city,
        /**
         * Required. Beyond being basic location data, this is the first half
         * of the intended defence against a transposed boundary: Nigeria's
         * longitude and latitude ranges overlap (4–14), so a country-level
         * bounds check cannot reject a swapped interior point, but a
         * per-state bounding box can — FCT's is small enough that transposed
         * Abuja falls outside it. That check can only be built once every
         * estate actually carries a state, which is why this is mandatory
         * before the boundary work it enables. See AGENTS.md.
         */
        @NotBlank String state,
        String address,
        @PositiveOrZero BigDecimal cornerPremiumPct,
        String intent,
        List<String> amenities,
        UUID branchId,
        @Valid GeoJsonPolygonDto footprint
) {
}
