package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** {@code POST /api/portal/estates/{id}/boundary} body — for an estate created without one. */
public record AddEstateBoundaryRequest(
        @Schema(description = "GeoJSON Polygon, coordinates in [longitude, latitude] order.")
        @NotNull @Valid GeoJsonPolygonDto footprint
) {
}
