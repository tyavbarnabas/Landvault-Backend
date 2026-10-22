package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PaymentPenaltyRepository extends JpaRepository<PaymentPenalty, UUID> {

    List<PaymentPenalty> findByPaymentIdIn(Collection<UUID> paymentIds);
}
