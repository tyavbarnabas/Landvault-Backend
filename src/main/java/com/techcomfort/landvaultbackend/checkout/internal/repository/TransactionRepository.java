package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByReservationId(UUID reservationId);

    Optional<Transaction> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);
}
