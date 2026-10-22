package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.TransactionInstalment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransactionInstalmentRepository extends JpaRepository<TransactionInstalment, UUID> {

    List<TransactionInstalment> findByTransactionIdInOrderBySequenceAsc(Collection<UUID> transactionIds);
}
