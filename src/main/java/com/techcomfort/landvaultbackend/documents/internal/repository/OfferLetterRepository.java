package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferLetterRepository extends JpaRepository<OfferLetter, UUID> {

    Optional<OfferLetter> findByTransactionId(UUID transactionId);

    List<OfferLetter> findByTransactionIdIn(Collection<UUID> transactionIds);

    Optional<OfferLetter> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    Optional<OfferLetter> findByVerificationCode(String verificationCode);

    List<OfferLetter> findByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId);

    Optional<OfferLetter> findByBuyerUserIdAndTransactionId(UUID buyerUserId, UUID transactionId);

    @Query(value = "SELECT nextval('offer_letter_number_seq')", nativeQuery = true)
    long nextNumber();
}
