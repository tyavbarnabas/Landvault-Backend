package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.Receipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    boolean existsByPaymentId(UUID paymentId);

    Optional<Receipt> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    Optional<Receipt> findByVerificationCode(String verificationCode);

    Page<Receipt> findByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId, Pageable pageable);

    List<Receipt> findAllByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId);

    List<Receipt> findByBuyerUserIdAndTransactionIdOrderByIssuedAtDesc(UUID buyerUserId, UUID transactionId);

    List<Receipt> findAllByPaymentIdIn(Collection<UUID> paymentIds);

    @Query(value = "SELECT nextval('receipt_number_seq')", nativeQuery = true)
    long nextNumber();
}
