package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code PUT /api/portal/estates/{id}/boundary}: replaces an existing boundary. */
public record CorrectEstateBoundaryRequest(
        @Schema(description = "The corrected boundary. GeoJSON Polygon, coordinates in [longitude, latitude] order.")
        @NotNull @Valid GeoJsonPolygonDto footprint,
        @Schema(description = "Why it changed — e.g. 'Re-survey corrected the north-east pillar.' Kept in the "
                + "boundary history and shown to a reviewer.")
        @NotBlank @Size(max = 500) String reason
) {
}
