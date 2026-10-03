package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** {@code PUT .../plots/{plotId}/boundary} body (IE-9). A boundary cannot be removed, only replaced. */
public record CorrectPlotBoundaryRequest(
        @Schema(description = "GeoJSON Polygon, coordinates in [longitude, latitude] order.")
        @NotNull @Valid GeoJsonPolygonDto footprint
) {
}
