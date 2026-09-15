package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    // An organization IS the tenant (see AGENTS.md's naming-split note), so
    // "does this branch belong to this tenant" is exactly "does this
    // branch's organizationId equal this tenantId" — no separate tenant
    // table to join against.
    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);
}
