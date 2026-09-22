package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.AmenityView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AmenityViewRepository extends Repository<AmenityView, UUID> {

    List<AmenityView> findByEstateIdInOrderByNameAsc(Collection<UUID> estateIds);
}
