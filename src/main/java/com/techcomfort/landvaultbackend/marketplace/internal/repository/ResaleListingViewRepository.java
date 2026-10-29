package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.ResaleListingView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResaleListingViewRepository extends Repository<ResaleListingView, UUID> {

    Optional<ResaleListingView> findById(UUID listingId);

    List<ResaleListingView> findByListingIdIn(Collection<UUID> listingIds);

    List<ResaleListingView> findByEstateIdIn(Collection<UUID> estateIds);
}
