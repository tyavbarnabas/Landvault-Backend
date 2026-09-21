package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlotRepository extends JpaRepository<Plot, UUID>, JpaSpecificationExecutor<Plot> {

    Optional<Plot> findByIdAndEstateId(UUID id, UUID estateId);

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

    /** Only plots that actually have a boundary — the rest have nothing to draw. */
    List<Plot> findByEstateIdAndFootprintIsNotNull(UUID estateId);
}
