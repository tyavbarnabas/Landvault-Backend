package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Not RLS-policied (buyer-owned, like transactions); reached by the buyer's transaction or by reference. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The newest attempt in a given state — used to reuse an unpaid link rather than open a second payment. */
    Optional<Payment> findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(UUID transactionId, PaymentStatus status);

    /**
     * Locked, so the return page and Paystack's webhook confirming the same
     * payment at the same moment take turns: the second sees the first's
     * outcome and does nothing (PY-4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.reference = :reference")
    Optional<Payment> findByReferenceForUpdate(@Param("reference") String reference);

    /** The sweep's lock: every open attempt on a transaction, so nothing confirms them meanwhile. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Payment> findLockedByTransactionIdAndStatus(UUID transactionId, PaymentStatus status);

    boolean existsByTransactionIdAndStatus(UUID transactionId, PaymentStatus status);

    /** Refunds owed: confirmed payments marked as owed back, oldest first. Whether one is already under way is the caller's check. */
    List<Payment> findAllByStatusAndRefundRequestedAtIsNotNullOrderByRefundRequestedAtAsc(PaymentStatus status);

    Optional<Payment> findByReference(String reference);

    /** Late money for one company: confirmed, flagged for review, and not yet sent to refund. */
    List<Payment> findAllBySellerTenantIdAndStatusAndReviewReasonIsNotNullAndRefundRequestedAtIsNull(
            UUID sellerTenantId, PaymentStatus status);
}
