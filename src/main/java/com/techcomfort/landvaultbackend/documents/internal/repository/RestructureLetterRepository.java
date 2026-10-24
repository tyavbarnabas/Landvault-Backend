package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.RestructureLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RestructureLetterRepository extends JpaRepository<RestructureLetter, UUID> {

    Optional<RestructureLetter> findByRestructureId(UUID restructureId);

    Optional<RestructureLetter> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    Optional<RestructureLetter> findByVerificationCode(String verificationCode);

    List<RestructureLetter> findByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId);

    List<RestructureLetter> findByBuyerUserIdAndTransactionIdOrderByIssuedAtDesc(UUID buyerUserId, UUID transactionId);

    @Query(value = "SELECT nextval('restructure_letter_number_seq')", nativeQuery = true)
    long nextNumber();
}
