package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.TransactionPenaltyStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransactionPenaltyStepRepository extends JpaRepository<TransactionPenaltyStep, UUID> {

    List<TransactionPenaltyStep> findByTransactionIdIn(Collection<UUID> transactionIds);
}
