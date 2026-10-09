package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.Payout;
import com.techcomfort.landvaultbackend.payments.internal.enums.PayoutStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RLS-policied (changeset 076): platform staff send payouts; a company may read its own. */
public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    /** Locked, so two Super Admins acting on the same payout take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.id = :id")
    Optional<Payout> findByIdForUpdate(@Param("id") UUID id);

    /** Every attempt for a sale, oldest first, locked while the next part is decided. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.transactionId = :transactionId ORDER BY p.createdAt")
    List<Payout> findAllLockedByTransactionId(@Param("transactionId") UUID transactionId);

    List<Payout> findAllByTransactionIdInOrderByCreatedAtDesc(Collection<UUID> transactionIds);

    List<Payout> findAllByStatusOrderByCreatedAtDesc(PayoutStatus status);

    List<Payout> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.reference = :reference")
    Optional<Payout> findByReferenceForUpdate(@Param("reference") String reference);

    /** Another attempt for the same part that is live or paid — the rare double-payment check. */
    @Query("SELECT count(p) > 0 FROM Payout p WHERE p.transactionId = :transactionId AND p.partNumber = :part "
            + "AND p.id <> :id AND p.status IN :statuses")
    boolean existsOtherForPart(@Param("transactionId") UUID transactionId, @Param("part") int part,
                               @Param("id") UUID id, @Param("statuses") Collection<PayoutStatus> statuses);

    /** TR-3's sweep: no reply from Paystack for a while, or accepted but not finished for a while. */
    @Query("SELECT p.reference FROM Payout p WHERE (p.status = :sending AND p.createdAt < :sendingBefore) "
            + "OR (p.status = :pending AND p.updatedAt < :pendingBefore) ORDER BY p.createdAt")
    List<String> findStuck(@Param("sending") PayoutStatus sending, @Param("sendingBefore") Instant sendingBefore,
                           @Param("pending") PayoutStatus pending, @Param("pendingBefore") Instant pendingBefore);

    List<Payout> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
