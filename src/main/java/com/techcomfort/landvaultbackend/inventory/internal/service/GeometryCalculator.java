package com.techcomfort.landvaultbackend.inventory.internal.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The two PostGIS calls this slice needs. Both go through the shared
 * {@link EntityManager} rather than a separately acquired connection, so they
 * run inside the caller's transaction — the same reasoning as
 * {@code MeController}'s tenant-scope read, where a {@code JdbcTemplate}
 * silently ran on a different connection.
 */
@Component
public class GeometryCalculator {

    private static final int SRID_WGS84 = 4326;
    private static final int AREA_SCALE = 2;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Surveyed area in <strong>square metres</strong>, via the geography
     * cast.
     * <p>
     * {@code ST_Area} on raw 4326 geometry returns square <em>degrees</em> —
     * not an error, just a plausible-looking wrong number, which is precisely
     * what makes it dangerous. The {@code ::geography} cast is what makes the
     * result metres. See AGENTS.md.
     */
    public BigDecimal areaInSquareMetres(Polygon polygon) {
        if (polygon == null) {
            return null;
        }
        Object result = entityManager
                .createNativeQuery("SELECT ST_Area(ST_GeomFromText(:wkt, :srid)::geography)")
                .setParameter("wkt", polygon.toText())
                .setParameter("srid", SRID_WGS84)
                .getSingleResult();
        return new BigDecimal(result.toString()).setScale(AREA_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Whether the plot's boundary sits inside the estate's. A plot outside
     * its own estate is a data error worth catching at the door.
     * <p>
     * Not plot-against-plot overlap — that's conflict detection, which needs
     * its own design and its own slice.
     */
    public boolean isWithin(Polygon inner, Polygon outer) {
        Object result = entityManager
                .createNativeQuery(
                        "SELECT ST_Within(ST_GeomFromText(:inner, :srid), ST_GeomFromText(:outer, :srid))")
                .setParameter("inner", inner.toText())
                .setParameter("outer", outer.toText())
                .setParameter("srid", SRID_WGS84)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
