package com.techcomfort.landvaultbackend.resale.internal.repository;

import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResaleHoldRepository extends JpaRepository<ResaleHold, UUID> {

    Optional<ResaleHold> findByReservationId(UUID reservationId);

    Optional<ResaleHold> findByListingIdAndEndedAtIsNull(UUID listingId);

    List<ResaleHold> findByListingIdInAndEndedAtIsNull(Collection<UUID> listingIds);

    /** Every hold on these purchases' plots, for the flexible plan's paused clock. */
    List<ResaleHold> findBySellerTransactionIdIn(Collection<UUID> sellerTransactionIds);
}
