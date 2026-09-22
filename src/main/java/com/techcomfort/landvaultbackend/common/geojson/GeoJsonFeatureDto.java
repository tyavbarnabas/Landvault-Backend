package com.techcomfort.landvaultbackend.common.geojson;

import java.util.Map;

/**
 * One GeoJSON {@code Feature}: a geometry plus free-form properties, per
 * RFC 7946.
 * <p>
 * {@code properties} is a map because the GeoJSON spec says it is an
 * arbitrary JSON object — a mapping client reads whichever keys it knows and
 * ignores the rest. Values written here are already wire values (enum
 * {@code .getValue()}, never the Java constant name), the same as every
 * other DTO in this module.
 */
public record GeoJsonFeatureDto(
        String type,
        GeoJsonPolygonDto geometry,
        Map<String, Object> properties
) {

    public static GeoJsonFeatureDto of(GeoJsonPolygonDto geometry, Map<String, Object> properties) {
        return new GeoJsonFeatureDto("Feature", geometry, properties);
    }
}
