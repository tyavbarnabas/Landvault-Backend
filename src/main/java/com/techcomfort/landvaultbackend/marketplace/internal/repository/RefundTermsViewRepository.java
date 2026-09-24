package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.RefundTermsView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RefundTermsViewRepository extends Repository<RefundTermsView, UUID> {

    List<RefundTermsView> findByEstateIdIn(Collection<UUID> estateIds);
}
