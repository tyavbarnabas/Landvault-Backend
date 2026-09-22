package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.PriceTierView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PriceTierViewRepository extends Repository<PriceTierView, UUID> {

    List<PriceTierView> findByEstateIdInOrderByPriceAsc(Collection<UUID> estateIds);
}
