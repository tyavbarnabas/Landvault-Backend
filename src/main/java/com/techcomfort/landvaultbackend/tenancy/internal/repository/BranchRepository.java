package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    // An organization IS the tenant (see AGENTS.md's naming-split note), so
    // "does this branch belong to this tenant" is exactly "does this
    // branch's organizationId equal this tenantId" — no separate tenant
    // table to join against.
    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Branch> findByOrganizationId(UUID organizationId);

    // One grouped query for a whole page of organizations, not one COUNT
    // per row — see AGENTS.md's "avoid N+1 on the directory" note.
    @Query("SELECT new com.techcomfort.landvaultbackend.tenancy.internal.repository.BranchCountProjection(b.organizationId, COUNT(b)) "
            + "FROM Branch b WHERE b.organizationId IN :organizationIds GROUP BY b.organizationId")
    List<BranchCountProjection> countGroupedByOrganizationId(@Param("organizationIds") Collection<UUID> organizationIds);
}
