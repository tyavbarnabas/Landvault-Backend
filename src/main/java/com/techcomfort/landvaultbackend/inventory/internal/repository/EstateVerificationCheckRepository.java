package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateVerificationCheck;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationCheckType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EstateVerificationCheckRepository extends JpaRepository<EstateVerificationCheck, UUID> {

    Optional<EstateVerificationCheck> findByEstateIdAndCheckType(UUID estateId, VerificationCheckType checkType);
}
