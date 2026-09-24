package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateRefundTerms;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EstateRefundTermsRepository extends JpaRepository<EstateRefundTerms, UUID> {

    /** The current policy: the highest version declared for this estate. */
    Optional<EstateRefundTerms> findFirstByEstateIdOrderByVersionDesc(UUID estateId);
}
