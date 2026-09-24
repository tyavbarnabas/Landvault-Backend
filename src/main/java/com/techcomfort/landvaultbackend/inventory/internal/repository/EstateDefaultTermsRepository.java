package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateDefaultTerms;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EstateDefaultTermsRepository extends JpaRepository<EstateDefaultTerms, UUID> {

    Optional<EstateDefaultTerms> findFirstByEstateIdOrderByVersionDesc(UUID estateId);
}
