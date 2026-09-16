package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationFinancial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationFinancialRepository extends JpaRepository<OrganizationFinancial, UUID> {

    Optional<OrganizationFinancial> findByOrganizationId(UUID organizationId);
}
