package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRevocationRepository extends JpaRepository<PlanRevocation, UUID> {

    Optional<PlanRevocation> findByTransactionIdAndStatus(UUID transactionId, RevocationStatus status);

    /** The open notice, locked: starting, cancelling, confirming and clearing take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PlanRevocation r WHERE r.transactionId = :transactionId AND r.status = 'NOTICE'")
    Optional<PlanRevocation> findOpenLocked(@Param("transactionId") UUID transactionId);

    List<PlanRevocation> findByTransactionIdInAndStatus(Collection<UUID> transactionIds, RevocationStatus status);

    List<PlanRevocation> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
}
