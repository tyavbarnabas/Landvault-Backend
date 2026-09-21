package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstateRepository extends JpaRepository<Estate, UUID>, JpaSpecificationExecutor<Estate> {

    // Slug is unique per tenant, not globally — two developers may both have
    // a "Palm Grove".
    boolean existsByTenantIdAndSlugIgnoreCase(UUID tenantId, String slug);

    Optional<Estate> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Footprint areas in square metres for a whole page of estates in one
     * round trip, returned as {@code [id, area]} rows. Estates with no
     * boundary are simply absent — the caller leaves their area null rather
     * than reporting zero, which would read as "surveyed at nothing".
     * <p>
     * Native, because {@code ST_Area(...::geography)} has no JPQL
     * equivalent — and native means {@code @SQLRestriction} does
     * <strong>not</strong> apply, hence the explicit {@code deleted = false}.
     * RLS still applies (it is enforced by Postgres, not by Hibernate), so
     * this cannot reach another tenant's rows.
     */
    @Query(value = """
            SELECT id, ST_Area(footprint::geography)
            FROM estates
            WHERE id IN (:ids) AND deleted = false AND footprint IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> footprintAreasSqm(@Param("ids") Collection<UUID> ids);
}
