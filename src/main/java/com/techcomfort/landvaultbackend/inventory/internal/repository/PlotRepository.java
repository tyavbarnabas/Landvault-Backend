package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlotRepository extends JpaRepository<Plot, UUID>, JpaSpecificationExecutor<Plot> {

    Optional<Plot> findByIdAndEstateId(UUID id, UUID estateId);

    /**
     * The plot row under {@code SELECT ... FOR UPDATE} — the same lock
     * {@code landvault_reserve_plot} takes — so an edit and a reservation
     * of the same plot serialize instead of interleaving (IE-9/IE-10).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Plot p WHERE p.id = :id AND p.estateId = :estateId")
    Optional<Plot> findForUpdate(@Param("id") UUID id, @Param("estateId") UUID estateId);

    /**
     * Plot counts per status for a whole page of estates in one query,
     * returned as {@code [estateId, status, count]} rows — never a count
     * query per estate.
     * <p>
     * JPQL, so {@code @SQLRestriction("deleted = false")} applies and
     * soft-deleted plots are excluded without this query having to remember
     * to say so.
     */
    @Query("""
            SELECT p.estateId, p.status, COUNT(p)
            FROM Plot p
            WHERE p.estateId IN :estateIds
            GROUP BY p.estateId, p.status
            """)
    List<Object[]> countGroupedByEstateIdAndStatus(@Param("estateIds") Collection<UUID> estateIds);

    /**
     * Plot counts per status for one tier — what a tier edit would reach
     * (IE-2). Same {@code [status, count]} shape as the estate-level query.
     */
    @Query("""
            SELECT p.status, COUNT(p)
            FROM Plot p
            WHERE p.priceTierId = :tierId
            GROUP BY p.status
            """)
    List<Object[]> countGroupedByStatusForTier(@Param("tierId") UUID tierId);

    /**
     * A tier size change, applied to <strong>available plots only</strong>
     * (IE-4): a reserved or sold plot's size is what its buyer agreed to, and
     * the reservation captures price but not size.
     * <p>
     * One statement rather than load-and-save, and that is the concurrency
     * guarantee: a plot being reserved at this instant holds its row lock,
     * so this waits, re-checks the status against the committed row, and
     * skips it if it is now {@code RESERVED}. Returns how many were updated.
     * <p>
     * Native, and so {@code deleted = false} is spelled out rather than
     * inherited from {@code @SQLRestriction}. It runs under the caller's
     * row-level security, which is what confines it to their own tenant.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE plots
               SET nominal_size_sqm = :sizeSqm,
                   updated_at = now(),
                   updated_by = :actor
             WHERE price_tier_id = :tierId
               AND deleted = false
               AND status IN ('AVAILABLE_DEV', 'AVAILABLE_INV')
            """, nativeQuery = true)
    int resizeAvailablePlotsOnTier(@Param("tierId") UUID tierId,
                                   @Param("sizeSqm") BigDecimal sizeSqm,
                                   @Param("actor") String actor);

    /** Every plot on an estate — the import checks plot numbers and overlaps against them. */
    List<Plot> findByEstateId(UUID estateId);

    /** Only plots that actually have a boundary — the rest have nothing to draw. */
    List<Plot> findByEstateIdAndFootprintIsNotNull(UUID estateId);
}
