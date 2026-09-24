package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateDefaultPenaltyTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EstateDefaultPenaltyTierRepository
        extends JpaRepository<EstateDefaultPenaltyTier, UUID> {

    List<EstateDefaultPenaltyTier> findByDefaultTermsIdOrderByMonthsLateAsc(UUID defaultTermsId);
}
