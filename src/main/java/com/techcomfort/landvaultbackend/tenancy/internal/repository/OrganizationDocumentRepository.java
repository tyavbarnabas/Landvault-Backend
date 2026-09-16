package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrganizationDocumentRepository extends JpaRepository<OrganizationDocument, UUID> {

    List<OrganizationDocument> findByOrganizationId(UUID organizationId);
}
