package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
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

/** Not RLS-policied (buyer-owned, like payments): reached by the buyer's own payment, or by platform staff. */
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    /** The payment's refund that isn't failed — at most one, by the unique index — locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.paymentId = :paymentId AND r.status <> :failed")
    Optional<Refund> findLiveForUpdate(@Param("paymentId") UUID paymentId, @Param("failed") RefundStatus failed);

    List<Refund> findAllByPaymentIdInOrderByCreatedAtDesc(Collection<UUID> paymentIds);

    List<Refund> findAllByStatusOrderByCreatedAtDesc(RefundStatus status);

    List<Refund> findAllByOrderByCreatedAtDesc();

    /** Refunds with no reply for a while, or accepted but not finished for a while (needs-attention waits on the buyer). */
    @Query("SELECT r.id FROM Refund r WHERE (r.status = :sending AND r.createdAt < :sendingBefore) "
            + "OR (r.status IN :open AND r.updatedAt < :openBefore) ORDER BY r.createdAt")
    List<UUID> findStuck(@Param("sending") RefundStatus sending, @Param("sendingBefore") Instant sendingBefore,
                         @Param("open") Collection<RefundStatus> open, @Param("openBefore") Instant openBefore);
}
