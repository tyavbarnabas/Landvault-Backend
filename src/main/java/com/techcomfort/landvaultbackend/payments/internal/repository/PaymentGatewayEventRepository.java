package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentGatewayEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Insert and read only — the database refuses the app role UPDATE and DELETE (changeset 071). */
public interface PaymentGatewayEventRepository extends JpaRepository<PaymentGatewayEvent, UUID> {
}
