package com.techcomfort.landvaultbackend.resale.internal.repository;

import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResaleListingRepository extends JpaRepository<ResaleListing, UUID> {

    List<ResaleListing> findBySellerUserIdOrderByCreatedAtDesc(UUID sellerUserId);

    Optional<ResaleListing> findByIdAndSellerUserId(UUID id, UUID sellerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ResaleListing l WHERE l.id = :id AND l.sellerUserId = :seller")
    Optional<ResaleListing> findLockedByIdAndSellerUserId(@Param("id") UUID id, @Param("seller") UUID seller);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ResaleListing l WHERE l.id = :id")
    Optional<ResaleListing> findLockedById(@Param("id") UUID id);

    boolean existsByTransactionIdAndStatusIn(UUID transactionId, Collection<ListingStatus> statuses);

    /** Has any listing of this purchase ever paused the inactivity clock (only the first one may). */
    boolean existsByTransactionIdAndPausesInactivityTrue(UUID transactionId);

    List<ResaleListing> findByStatusIn(Collection<ListingStatus> statuses);

    // --- the developer's side (phase 4): only ever by the caller's own company ---

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ResaleListing l WHERE l.id = :id AND l.sellerTenantId = :tenant")
    Optional<ResaleListing> findLockedByIdAndSellerTenantId(@Param("id") UUID id, @Param("tenant") UUID tenant);

    Optional<ResaleListing> findByIdAndSellerTenantId(UUID id, UUID sellerTenantId);

    /** Waiting for consent first, the soonest due first; then everything else, newest first. */
    @Query(value = """
            SELECT l FROM ResaleListing l
            WHERE l.sellerTenantId = :tenant AND l.status IN :statuses
            ORDER BY CASE WHEN l.status = com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus.AWAITING_CONSENT
                          THEN 0 ELSE 1 END,
                     l.consentDueAt ASC NULLS LAST, l.createdAt DESC, l.id""",
            countQuery = """
            SELECT count(l) FROM ResaleListing l
            WHERE l.sellerTenantId = :tenant AND l.status IN :statuses""")
    Page<ResaleListing> findForDeveloper(@Param("tenant") UUID tenant,
                                         @Param("statuses") Collection<ListingStatus> statuses, Pageable pageable);

    /** The listings that paused the inactivity clock, for the flexible standings. */
    List<ResaleListing> findByTransactionIdInAndPausesInactivityTrue(Collection<UUID> transactionIds);
}
