package com.techcomfort.landvaultbackend.common.geojson;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The inverse of {@link GeoJsonPolygonParser} — a stored boundary back out
 * as the same {@link GeoJsonPolygonDto} shape it arrived in, so a POST and
 * the matching GET agree coordinate for coordinate.
 * <p>
 * <strong>Why not {@code ST_AsGeoJSON}, which is the obvious tool here.</strong>
 * Two reasons, both found rather than assumed:
 * <ol>
 *   <li><em>It rounds.</em> {@code ST_AsGeoJSON} defaults to 9 decimal
 *       places, so the document it produces is not necessarily the geometry
 *       that was stored. Reading the JTS geometry Hibernate already
 *       materialised keeps full {@code double} precision, which makes the
 *       round-trip test an actual equality check instead of an
 *       approximate one.</li>
 *   <li><em>Its output is a JSON string, and the properties beside it are
 *       not.</em> Embedding a pre-rendered JSON fragment inside a typed
 *       response means either {@code @JsonRawValue} — whose behaviour under
 *       Spring Boot 4.1's {@code tools.jackson} stack this codebase has
 *       explicitly flagged as unverified (AGENTS.md) — or building the whole
 *       document in SQL, which would put the enum wire-value mapping
 *       ({@code AVAILABLE_DEV} → {@code "available-dev"}) into a hand-written
 *       {@code CASE} that a new enum constant would silently fall out of.</li>
 * </ol>
 * Coordinates go out as {@code [longitude, latitude]} — GeoJSON order,
 * matching the parser's input contract exactly. JTS holds longitude in
 * {@code x} and latitude in {@code y}, which is how the parser built them.
 */
public final class GeoJsonPolygonWriter {

    private GeoJsonPolygonWriter() {
    }

    public static GeoJsonPolygonDto toGeoJson(Polygon polygon) {
        if (polygon == null) {
            return null;
        }
        List<List<List<BigDecimal>>> rings = new ArrayList<>(1 + polygon.getNumInteriorRing());
        rings.add(ring(polygon.getExteriorRing()));
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            rings.add(ring(polygon.getInteriorRingN(i)));
        }
        return new GeoJsonPolygonDto("Polygon", rings);
    }

    private static List<List<BigDecimal>> ring(LineString ring) {
        List<List<BigDecimal>> positions = new ArrayList<>(ring.getNumPoints());
        for (int i = 0; i < ring.getNumPoints(); i++) {
            Coordinate coordinate = ring.getCoordinateN(i);
            // BigDecimal.valueOf(double) goes through Double.toString, which
            // is the shortest decimal that round-trips to the same double —
            // so this neither invents precision nor loses any. new
            // BigDecimal(double) would instead print the full binary
            // expansion (3.4000000000000003552713678800500929355621337890625),
            // which is technically exact and completely unreadable.
            positions.add(List.of(
                    BigDecimal.valueOf(coordinate.getX()),
                    BigDecimal.valueOf(coordinate.getY())));
        }
        return positions;
    }
}
