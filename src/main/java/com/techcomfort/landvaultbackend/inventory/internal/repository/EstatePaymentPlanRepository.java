package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstatePaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EstatePaymentPlanRepository extends JpaRepository<EstatePaymentPlan, UUID> {

    List<EstatePaymentPlan> findByEstateIdAndVersionOrderBySortOrderAsc(UUID estateId, Integer version);
}
