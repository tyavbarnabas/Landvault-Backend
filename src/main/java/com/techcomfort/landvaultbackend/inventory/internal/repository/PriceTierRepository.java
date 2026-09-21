package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface PriceTierRepository extends JpaRepository<PriceTier, UUID> {

    boolean existsByEstateIdAndSizeSqm(UUID estateId, BigDecimal sizeSqm);

    Optional<PriceTier> findByIdAndEstateId(UUID id, UUID estateId);
}
