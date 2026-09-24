package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.PenaltyTierView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PenaltyTierViewRepository extends Repository<PenaltyTierView, UUID> {

    List<PenaltyTierView> findByEstateIdInOrderByMonthsLateAsc(Collection<UUID> estateIds);
}
