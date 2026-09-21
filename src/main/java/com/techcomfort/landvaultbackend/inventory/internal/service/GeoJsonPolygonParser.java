package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.inventory.dto.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Turns a GeoJSON {@code Polygon} into a JTS {@link Polygon} at SRID 4326,
 * rejecting anything that isn't one.
 * <p>
 * GeoJSON is the primary boundary path deliberately: it's what surveyors'
 * tools export, PostGIS understands it natively, and the frontend already
 * models boundaries as a coordinate ring. File upload (.geojson, shapefile,
 * CAD) is a follow-up, not built here.
 * <p>
 * Built by hand from a typed DTO rather than through a GeoJSON library,
 * because every check below is one this domain needs specifically — a library
 * would parse the Somalia case perfectly happily.
 */
@Component
public class GeoJsonPolygonParser {

    private static final int SRID_WGS84 = 4326;
    private static final String POLYGON_TYPE = "Polygon";
    private static final int MIN_POSITIONS = 4;

    /**
     * Nigeria's rough bounding box. This is the practical guard against the
     * {@code [lat, lng]}/{@code [lng, lat]} swap: a transposed Lagos
     * coordinate (6.5, 3.4) lands at longitude 6.5, latitude 3.4 — south of
     * the country, in the Gulf of Guinea — and fails immediately instead of
     * being stored as a plausible-looking polygon in the wrong place.
     */
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("2");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("15");
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("4");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("14");

    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    /** Returns {@code null} for a null input — a boundary is optional. */
    public Polygon parse(GeoJsonPolygonDto dto, String fieldName) {
        if (dto == null) {
            return null;
        }
        if (!POLYGON_TYPE.equalsIgnoreCase(dto.type())) {
            throw new InventoryException.InvalidGeometry(fieldName,
                    "must be a GeoJSON Polygon, got '" + dto.type() + "'. MultiPolygon, LineString and bare "
                            + "coordinate arrays are not accepted.");
        }
        if (dto.coordinates() == null || dto.coordinates().isEmpty()) {
            throw new InventoryException.InvalidGeometry(fieldName, "has no coordinate ring.");
        }

        LinearRing shell = ring(dto.coordinates().getFirst(), fieldName, "outer ring");
        LinearRing[] holes = new LinearRing[dto.coordinates().size() - 1];
        for (int i = 1; i < dto.coordinates().size(); i++) {
            holes[i - 1] = ring(dto.coordinates().get(i), fieldName, "hole " + i);
        }

        Polygon polygon = geometryFactory.createPolygon(shell, holes);
        polygon.setSRID(SRID_WGS84);
        return polygon;
    }

    private LinearRing ring(List<List<BigDecimal>> positions, String fieldName, String what) {
        if (positions == null || positions.size() < MIN_POSITIONS) {
            throw new InventoryException.InvalidGeometry(fieldName,
                    what + " needs at least " + MIN_POSITIONS + " positions (3 distinct corners plus the "
                            + "closing repeat), got " + (positions == null ? 0 : positions.size()) + ".");
        }

        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            List<BigDecimal> position = positions.get(i);
            if (position == null || position.size() < 2) {
                throw new InventoryException.InvalidGeometry(fieldName,
                        what + " position " + i + " must be [longitude, latitude].");
            }
            BigDecimal longitude = position.get(0);
            BigDecimal latitude = position.get(1);
            requireWithinNigeria(longitude, latitude, fieldName, what, i);
            coordinates[i] = new Coordinate(longitude.doubleValue(), latitude.doubleValue());
        }

        if (!coordinates[0].equals2D(coordinates[coordinates.length - 1])) {
            throw new InventoryException.InvalidGeometry(fieldName,
                    what + " is not closed — the first and last positions must be identical.");
        }
        return geometryFactory.createLinearRing(coordinates);
    }

    private void requireWithinNigeria(
            BigDecimal longitude, BigDecimal latitude, String fieldName, String what, int index) {
        boolean longitudeOk = longitude.compareTo(MIN_LONGITUDE) >= 0 && longitude.compareTo(MAX_LONGITUDE) <= 0;
        boolean latitudeOk = latitude.compareTo(MIN_LATITUDE) >= 0 && latitude.compareTo(MAX_LATITUDE) <= 0;
        if (longitudeOk && latitudeOk) {
            return;
        }
        // The swap is by far the likeliest cause, so say so rather than
        // leaving the caller to work it out from a bounds number.
        boolean looksSwapped = latitude.compareTo(MIN_LONGITUDE) >= 0 && latitude.compareTo(MAX_LONGITUDE) <= 0
                && longitude.compareTo(MIN_LATITUDE) >= 0 && longitude.compareTo(MAX_LATITUDE) <= 0;
        throw new InventoryException.InvalidGeometry(fieldName,
                what + " position " + index + " [" + longitude + ", " + latitude + "] is outside Nigeria"
                        + (looksSwapped
                        ? " — the coordinates look transposed. GeoJSON order is [longitude, latitude]; "
                        + "Leaflet's [latitude, longitude] is the other way round."
                        : " (expected longitude " + MIN_LONGITUDE + "-" + MAX_LONGITUDE
                        + ", latitude " + MIN_LATITUDE + "-" + MAX_LATITUDE + ").")
                        + "");
    }
}
