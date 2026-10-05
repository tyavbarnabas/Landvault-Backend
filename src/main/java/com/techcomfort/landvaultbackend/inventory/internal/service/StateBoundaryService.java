package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.common.NigerianStates;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.locationtech.jts.geom.Polygon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SB-1: Nigeria's states, from the {@code nigerian_states} reference table
 * (GRID3 via geoBoundaries, CC BY 4.0 — changeset 064). Two jobs:
 * <ul>
 *   <li><strong>Standardise a state name</strong>: "FCT", "Abuja" and "Lagos
 *       State" all resolve to the canonical name and ISO code; anything else
 *       is refused rather than stored as free text nobody can check.</li>
 *   <li><strong>Check a boundary sits inside its declared state</strong>,
 *       within {@code margin-metres} (default 1 km). Transposed coordinates
 *       that stay inside Nigeria land hundreds of kilometres away in another
 *       state, so the margin absorbs border-data imprecision and estates on a
 *       border without hiding a swap.</li>
 * </ul>
 * The check is skipped for an estate a Super Admin has verified by hand
 * ({@code stateOverrideAt}) — the escape for genuinely disputed borders.
 * See AGENTS.md.
 */
@Component
public class StateBoundaryService {

    private static final int SRID_WGS84 = 4326;

    @PersistenceContext
    private EntityManager entityManager;

    private final NigerianStates states;

    public StateBoundaryService(NigerianStates states) {
        this.states = states;
    }

    @Value("${landvault.estates.state-check.enabled:true}")
    private boolean enabled;

    @Value("${landvault.estates.state-check.margin-metres:1000}")
    private double marginMetres;

    public record ResolvedState(String code, String name) {
    }

    /** The canonical state for what a caller typed, or a refusal listing the valid names. */
    public ResolvedState resolve(String input) {
        return states.resolve(input)
                .map(state -> new ResolvedState(state.code(), state.name()))
                .orElseThrow(() -> new InventoryException.ImmutableField("UNKNOWN_STATE",
                        "'" + (input == null ? "" : input.trim()) + "' isn't a Nigerian state. Use one of: "
                                + states.names() + "."));
    }

    /**
     * Refuses a boundary that isn't inside the estate's declared state
     * (plus the margin), naming the state it actually falls in. A no-op when
     * the check is disabled, the estate has no recognised state, or a Super
     * Admin has verified its state.
     */
    public void requireWithinDeclaredState(Polygon footprint, Estate estate) {
        if (!enabled || footprint == null || estate.getStateCode() == null || estate.getStateOverrideAt() != null) {
            return;
        }
        Object within = entityManager.createNativeQuery("""
                        SELECT ST_Within(ST_GeomFromText(:wkt, :srid),
                                         ST_Buffer(boundary::geography, :margin)::geometry)
                          FROM nigerian_states WHERE code = :code
                        """)
                .setParameter("wkt", footprint.toText())
                .setParameter("srid", SRID_WGS84)
                .setParameter("margin", marginMetres)
                .setParameter("code", estate.getStateCode())
                .getSingleResult();
        if (Boolean.TRUE.equals(within)) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> actual = entityManager.createNativeQuery("""
                        SELECT name FROM nigerian_states
                         WHERE ST_Intersects(boundary, ST_GeomFromText(:wkt, :srid))
                         ORDER BY ST_Area(ST_Intersection(boundary, ST_GeomFromText(:wkt, :srid))) DESC
                         LIMIT 1
                        """)
                .setParameter("wkt", footprint.toText())
                .setParameter("srid", SRID_WGS84)
                .getResultList();
        String where = actual.isEmpty() ? "outside every Nigerian state" : "in " + actual.getFirst();
        throw new InventoryException.ImmutableField("BOUNDARY_OUTSIDE_STATE",
                "This boundary sits " + where + ", not " + estate.getState() + ". Check the coordinates — "
                        + "GeoJSON order is [longitude, latitude], and swapped coordinates often land in another "
                        + "state — or correct the estate's state. If the land is genuinely on a disputed border, "
                        + "create the estate without a boundary and contact support.");
    }
}
