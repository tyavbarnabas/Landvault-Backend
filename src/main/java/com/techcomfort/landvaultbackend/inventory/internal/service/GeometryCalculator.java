package com.techcomfort.landvaultbackend.inventory.internal.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

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
    /**
     * Area of any geometry in square metres (geography cast) — for an overlap,
     * which can come out as a polygon, multipolygon or collection. Null for
     * an empty one.
     */
    public BigDecimal areaOf(org.locationtech.jts.geom.Geometry shape) {
        if (shape == null || shape.isEmpty()) {
            return null;
        }
        Object result = entityManager
                .createNativeQuery("SELECT ST_Area(ST_GeomFromText(:wkt, :srid)::geography)")
                .setParameter("wkt", shape.toText())
                .setParameter("srid", SRID_WGS84)
                .getSingleResult();
        return new BigDecimal(result.toString()).setScale(AREA_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Every plot on the estate whose boundary does not sit inside
     * {@code boundary}, labelled "Block X, Plot N" (or "Plot N" with no
     * block). One statement for the whole estate rather than one per plot —
     * an estate can hold hundreds. Plots without a boundary are not checked:
     * there is nothing to compare.
     */
    public List<String> plotsOutside(UUID estateId, Polygon boundary) {
        @SuppressWarnings("unchecked")
        List<Object> labels = entityManager
                .createNativeQuery("""
                        SELECT CASE WHEN b.name IS NULL THEN 'Plot ' || p.plot_number
                                    ELSE 'Block ' || b.name || ', Plot ' || p.plot_number END
                          FROM plots p
                          LEFT JOIN blocks b ON b.id = p.block_id
                         WHERE p.estate_id = :estateId
                           AND p.deleted = false
                           AND p.footprint IS NOT NULL
                           AND NOT ST_Within(p.footprint, ST_GeomFromText(:boundary, :srid))
                         ORDER BY b.name NULLS FIRST, p.plot_number
                        """)
                .setParameter("estateId", estateId)
                .setParameter("boundary", boundary.toText())
                .setParameter("srid", SRID_WGS84)
                .getResultList();
        return labels.stream().map(Object::toString).toList();
    }

    /**
     * How much land differs between two boundaries, in square metres: the
     * area of their symmetric difference (land added plus land removed), so
     * a boundary that shifts sideways counts as changed even if its area
     * doesn't. Geography cast — see {@link #areaInSquareMetres}.
     */
    public BigDecimal changedArea(Polygon before, Polygon after) {
        Object result = entityManager
                .createNativeQuery("SELECT ST_Area(ST_SymDifference(ST_GeomFromText(:a, :srid), "
                        + "ST_GeomFromText(:b, :srid))::geography)")
                .setParameter("a", before.toText())
                .setParameter("b", after.toText())
                .setParameter("srid", SRID_WGS84)
                .getSingleResult();
        return new BigDecimal(result.toString()).setScale(AREA_SCALE, RoundingMode.HALF_UP);
    }

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
