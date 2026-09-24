package com.techcomfort.landvaultbackend.common.geojson;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(
        name = "GeoJsonPolygon",
        description = """
                A GeoJSON Polygon, SRID 4326. **Coordinates are `[longitude, latitude]`** — \
                GeoJSON order, the opposite of Leaflet's `[latitude, longitude]`. Getting this \
                backwards produces no error at all, just a structurally valid polygon in the \
                wrong place, so the example below is worth copying rather than composing from \
                the field list. The ring must be closed (first position repeated last) and carry \
                at least four positions.

                This example is a real ~1 km square in Gwarinpa, Abuja.""",
        example = """
                {
                  "type": "Polygon",
                  "coordinates": [[
                    [7.400, 9.100],
                    [7.409, 9.100],
                    [7.409, 9.109],
                    [7.400, 9.109],
                    [7.400, 9.100]
                  ]]
                }""")
public record GeoJsonPolygonDto(
        @Schema(description = "Always `Polygon`.", example = "Polygon")
        @NotBlank String type,
        @Schema(description = "Linear rings. The first is the outer boundary, any others are "
                + "holes. Each position is `[longitude, latitude]`.")
        @NotNull List<List<List<BigDecimal>>> coordinates
) {
}
