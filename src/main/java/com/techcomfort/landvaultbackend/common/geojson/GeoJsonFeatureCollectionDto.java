package com.techcomfort.landvaultbackend.common.geojson;

import java.util.List;

/**
 * An estate's boundary and its plots' boundaries as one GeoJSON
 * {@code FeatureCollection}, ready to hand to Leaflet.
 * <p>
 * Coordinates go out in <strong>GeoJSON order, {@code [longitude, latitude]}</strong>
 * — the same order {@code GeoJsonPolygonDto} documents for input, so what
 * was posted is what comes back. The frontend converts for Leaflet; the
 * backend never does, in either direction.
 * <p>
 * Features with no boundary are <strong>omitted, not emitted empty</strong>:
 * a plot whose footprint hasn't been surveyed yet has no geometry, and a
 * {@code Feature} with a null geometry would render as a point at
 * {@code [0, 0]} in the Gulf of Guinea on most mapping clients.
 */
public record GeoJsonFeatureCollectionDto(String type, List<GeoJsonFeatureDto> features) {

    public static GeoJsonFeatureCollectionDto of(List<GeoJsonFeatureDto> features) {
        return new GeoJsonFeatureCollectionDto("FeatureCollection", features);
    }
}
