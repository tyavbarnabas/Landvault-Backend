package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationStateRegulator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrganizationStateRegulatorRepository extends JpaRepository<OrganizationStateRegulator, UUID> {

    List<OrganizationStateRegulator> findByOrganizationId(UUID organizationId);
}
