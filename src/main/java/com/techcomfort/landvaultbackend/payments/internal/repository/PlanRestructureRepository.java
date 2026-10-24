package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructure;
import com.techcomfort.landvaultbackend.payments.internal.enums.RestructureStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRestructureRepository extends JpaRepository<PlanRestructure, UUID> {

    /** The open request or proposal, locked: every step on it takes turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PlanRestructure r WHERE r.transactionId = :transactionId AND r.status IN ('REQUESTED', 'PROPOSED')")
    Optional<PlanRestructure> findOpenLocked(@Param("transactionId") UUID transactionId);

    List<PlanRestructure> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    List<PlanRestructure> findByTransactionIdInAndStatusIn(Collection<UUID> transactionIds,
                                                           Collection<RestructureStatus> statuses);

    List<PlanRestructure> findBySellerTenantIdAndStatusInOrderByRequestedAtAsc(UUID sellerTenantId,
                                                                               Collection<RestructureStatus> statuses);

    List<PlanRestructure> findByStatusAndExpiresOnBefore(RestructureStatus status, LocalDate day);

    List<PlanRestructure> findByStatusAndExpiresOnLessThanEqualAndLapseReminderSentAtIsNull(RestructureStatus status,
                                                                                          LocalDate day);

    List<PlanRestructure> findByLetterIdIn(Collection<UUID> letterIds);
}
