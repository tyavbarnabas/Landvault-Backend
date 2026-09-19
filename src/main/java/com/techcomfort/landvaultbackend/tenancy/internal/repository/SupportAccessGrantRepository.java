package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.SupportAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportAccessGrantRepository extends JpaRepository<SupportAccessGrant, UUID> {

    // Most recent first — how a Super Admin actually wants to see grants
    // for a tenant (an active grant near the top, not buried under history).
    List<SupportAccessGrant> findByOrganizationIdOrderByRequestedAtDesc(UUID organizationId);
}
