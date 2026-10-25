package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.PenaltyWaiver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PenaltyWaiverRepository extends JpaRepository<PenaltyWaiver, UUID> {

    List<PenaltyWaiver> findByTransactionIdIn(Collection<UUID> transactionIds);

    List<PenaltyWaiver> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
}
