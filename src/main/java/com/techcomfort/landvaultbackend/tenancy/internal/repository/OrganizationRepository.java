package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * {@code JpaSpecificationExecutor} for the directory's combinable filters
 * (free text, verification state, plan, state of operation, created-after)
 * — see {@link OrganizationSpecifications}.
 */
public interface OrganizationRepository extends JpaRepository<Organization, UUID>, JpaSpecificationExecutor<Organization> {

    // Case-insensitive — "RC1234567" and "rc1234567" are the same company
    // registration, not two different tenants.
    boolean existsByRcNumberIgnoreCase(String rcNumber);
}
