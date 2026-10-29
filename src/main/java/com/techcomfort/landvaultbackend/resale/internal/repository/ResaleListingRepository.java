package com.techcomfort.landvaultbackend.resale.internal.repository;

import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResaleListingRepository extends JpaRepository<ResaleListing, UUID> {

    List<ResaleListing> findBySellerUserIdOrderByListedAtDesc(UUID sellerUserId);

    Optional<ResaleListing> findByIdAndSellerUserId(UUID id, UUID sellerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ResaleListing l WHERE l.id = :id AND l.sellerUserId = :seller")
    Optional<ResaleListing> findLockedByIdAndSellerUserId(@Param("id") UUID id, @Param("seller") UUID seller);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ResaleListing l WHERE l.id = :id")
    Optional<ResaleListing> findLockedById(@Param("id") UUID id);

    boolean existsByTransactionIdAndStatus(UUID transactionId, ListingStatus status);

    /** Has any listing of this purchase ever paused the inactivity clock (only the first one may). */
    boolean existsByTransactionIdAndPausesInactivityTrue(UUID transactionId);

    List<ResaleListing> findByStatus(ListingStatus status);

    /** The listings that paused the inactivity clock, for the flexible standings. */
    List<ResaleListing> findByTransactionIdInAndPausesInactivityTrue(Collection<UUID> transactionIds);
}
