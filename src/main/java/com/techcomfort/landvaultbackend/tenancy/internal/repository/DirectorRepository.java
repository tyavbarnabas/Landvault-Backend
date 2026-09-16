package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.Director;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DirectorRepository extends JpaRepository<Director, UUID> {

    List<Director> findByOrganizationId(UUID organizationId);
}
