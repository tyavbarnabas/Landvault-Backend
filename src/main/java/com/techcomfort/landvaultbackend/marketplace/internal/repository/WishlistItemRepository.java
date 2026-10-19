package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.WishlistItem;
import com.techcomfort.landvaultbackend.marketplace.internal.enums.WishlistListingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, UUID> {

    List<WishlistItem> findByBuyerUserIdOrderBySavedAtDescIdDesc(UUID buyerUserId);

    Optional<WishlistItem> findByBuyerUserIdAndListingTypeAndListingId(UUID buyerUserId, WishlistListingType listingType,
                                                                       UUID listingId);

    long countByBuyerUserId(UUID buyerUserId);

    @Modifying
    @Query("DELETE FROM WishlistItem w WHERE w.buyerUserId = :buyer AND w.listingType = :type AND w.listingId = :listing")
    int deleteSaved(@Param("buyer") UUID buyerUserId, @Param("type") WishlistListingType listingType,
                    @Param("listing") UUID listingId);

    /**
     * Serialises one buyer's saves until the transaction ends, so two at once
     * can't both pass the size check. Namespaced, so it can't collide with
     * another feature's advisory lock.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('wishlist'), "
            + "hashtext(CAST(:buyer AS text)))) AS locked", nativeQuery = true)
    Integer lockBuyer(@Param("buyer") UUID buyerUserId);
}
