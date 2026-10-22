package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.AllocationLetter;
import com.techcomfort.landvaultbackend.documents.internal.enums.LetterKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AllocationLetterRepository extends JpaRepository<AllocationLetter, UUID> {

    boolean existsByTransactionIdAndKind(UUID transactionId, LetterKind kind);

    /** A sale's letters, newest first: the final one, then the provisional one before it. */
    List<AllocationLetter> findByTransactionIdOrderByIssuedAtDesc(UUID transactionId);

    Optional<AllocationLetter> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    Optional<AllocationLetter> findByVerificationCode(String verificationCode);

    List<AllocationLetter> findByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId);

    List<AllocationLetter> findByBuyerUserIdAndTransactionIdOrderByIssuedAtDesc(UUID buyerUserId, UUID transactionId);

    @Query(value = "SELECT nextval('allocation_letter_number_seq')", nativeQuery = true)
    long nextNumber();

    @Query(value = "SELECT nextval('provisional_letter_number_seq')", nativeQuery = true)
    long nextProvisionalNumber();
}
