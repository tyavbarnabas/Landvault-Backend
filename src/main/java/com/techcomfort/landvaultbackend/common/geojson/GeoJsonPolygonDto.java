package com.techcomfort.landvaultbackend.common.geojson;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * A GeoJSON {@code Polygon} as it arrives on the wire.
 * <p>
 * <strong>Coordinates are {@code [longitude, latitude]}</strong> — GeoJSON
 * order, not Leaflet's {@code [latitude, longitude]}. Getting this backwards
 * places an Abuja estate somewhere off Somalia with no error raised, just a
 * structurally valid polygon in the wrong hemisphere. The Nigeria bounds
 * check in {@code GeoJsonPolygonParser} exists to catch exactly that; the
 * frontend converts for Leaflet, the backend never does. See AGENTS.md.
 * <p>
 * {@code coordinates} is a list of linear rings — the first is the outer
 * boundary, any others are holes. The ring must be closed (first position
 * equals last) and carry at least four positions.
 */
public record GeoJsonPolygonDto(
        @NotBlank String type,
        @NotNull List<List<List<BigDecimal>>> coordinates
) {
}
