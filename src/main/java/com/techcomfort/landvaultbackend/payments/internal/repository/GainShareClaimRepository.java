package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.GainShareClaim;
import com.techcomfort.landvaultbackend.payments.internal.enums.GainShareStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Not RLS-policied (buyer-owned, like payments): reached by the buyer, or by the sale it is paid from. */
public interface GainShareClaimRepository extends JpaRepository<GainShareClaim, UUID> {

    /** A plot's open claims, oldest first, locked — two sales can't settle one claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GainShareClaim c WHERE c.plotId = :plotId AND c.status = 'OPEN' ORDER BY c.openedAt, c.id")
    List<GainShareClaim> findOpenLockedByPlotId(@Param("plotId") UUID plotId);

    /** Claims owed out of these sales' payouts. */
    List<GainShareClaim> findBySaleTransactionIdInAndStatus(Collection<UUID> saleTransactionIds, GainShareStatus status);

    List<GainShareClaim> findByBuyerUserIdOrderByOpenedAtDesc(UUID buyerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GainShareClaim c WHERE c.id = :id AND c.buyerUserId = :buyerUserId")
    Optional<GainShareClaim> findOwnForUpdate(@Param("id") UUID id, @Param("buyerUserId") UUID buyerUserId);

    boolean existsByClaimTransactionId(UUID claimTransactionId);
}
