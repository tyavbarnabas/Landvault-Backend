package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.AllocationLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AllocationLetterRepository extends JpaRepository<AllocationLetter, UUID> {

    boolean existsByTransactionId(UUID transactionId);

    Optional<AllocationLetter> findByTransactionId(UUID transactionId);

    Optional<AllocationLetter> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    Optional<AllocationLetter> findByVerificationCode(String verificationCode);

    List<AllocationLetter> findByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId);

    Optional<AllocationLetter> findByBuyerUserIdAndTransactionId(UUID buyerUserId, UUID transactionId);

    @Query(value = "SELECT nextval('allocation_letter_number_seq')", nativeQuery = true)
    long nextNumber();
}
