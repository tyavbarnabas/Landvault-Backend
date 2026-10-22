package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.PaymentPlanView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPlanViewRepository extends Repository<PaymentPlanView, UUID> {

    List<PaymentPlanView> findByEstateIdInOrderBySortOrderAsc(Collection<UUID> estateIds);

    Optional<PaymentPlanView> findByPlanIdAndEstateId(UUID planId, UUID estateId);
}
