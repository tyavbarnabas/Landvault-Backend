package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.EstateEligibilityView;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface EstateEligibilityViewRepository extends Repository<EstateEligibilityView, UUID> {

    Optional<EstateEligibilityView> findById(UUID estateId);
}
