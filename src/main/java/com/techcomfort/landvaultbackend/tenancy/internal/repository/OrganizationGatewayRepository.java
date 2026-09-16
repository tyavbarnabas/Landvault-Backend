package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationGateway;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrganizationGatewayRepository extends JpaRepository<OrganizationGateway, UUID> {

    List<OrganizationGateway> findByOrganizationId(UUID organizationId);
}
