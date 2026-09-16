package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationRegulatory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRegulatoryRepository extends JpaRepository<OrganizationRegulatory, UUID> {

    Optional<OrganizationRegulatory> findByOrganizationId(UUID organizationId);
}
