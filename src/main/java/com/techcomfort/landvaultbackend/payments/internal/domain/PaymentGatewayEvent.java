package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** A signed webhook exactly as received (changeset 071). Append-only: never updated or deleted. */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "payment_gateway_events")
public class PaymentGatewayEvent extends AbstractAppendOnlyEntity {

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "event_type", updatable = false)
    private String eventType;

    @Column(name = "reference", updatable = false)
    private String reference;

    @Column(name = "raw_body", nullable = false, updatable = false)
    private String rawBody;
}
