package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EstateRepository extends JpaRepository<Estate, UUID> {

    // Slug is unique per tenant, not globally — two developers may both have
    // a "Palm Grove".
    boolean existsByTenantIdAndSlugIgnoreCase(UUID tenantId, String slug);

    Optional<Estate> findByIdAndTenantId(UUID id, UUID tenantId);
}
