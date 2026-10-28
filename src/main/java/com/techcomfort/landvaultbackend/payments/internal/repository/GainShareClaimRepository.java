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

    /** A sale's owed claims, locked: a gain transfer starting and the buyer changing its account take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GainShareClaim c WHERE c.saleTransactionId = :saleTransactionId AND c.status = :status "
            + "ORDER BY c.openedAt, c.id")
    List<GainShareClaim> findLockedBySaleTransactionIdAndStatus(@Param("saleTransactionId") UUID saleTransactionId,
                                                                @Param("status") GainShareStatus status);

    /**
     * Payouts on one buyer's own claims (claim id, status, amount), through
     * landvault_buyer_gain_payouts (changeset 115): payouts is policied to
     * platform staff and the selling company, so the buyer's session can't
     * read it. Never a repository read of payouts on the buyer's side.
     */
    @Query(value = "SELECT gain_claim_id, status, amount FROM landvault_buyer_gain_payouts(:buyerUserId)",
            nativeQuery = true)
    List<Object[]> findPayoutsOnBuyersClaims(@Param("buyerUserId") UUID buyerUserId);

    List<GainShareClaim> findByBuyerUserIdOrderByOpenedAtDesc(UUID buyerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GainShareClaim c WHERE c.id = :id AND c.buyerUserId = :buyerUserId")
    Optional<GainShareClaim> findOwnForUpdate(@Param("id") UUID id, @Param("buyerUserId") UUID buyerUserId);

    boolean existsByClaimTransactionId(UUID claimTransactionId);
}
