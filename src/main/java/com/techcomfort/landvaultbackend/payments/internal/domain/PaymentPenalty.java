package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

/** A late-payment penalty a payment carried for one instalment, fixed when it started (changeset 104). */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "payment_penalties")
public class PaymentPenalty extends AbstractAppendOnlyEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "instalment_sequence", nullable = false, updatable = false)
    private Integer instalmentSequence;

    @Column(name = "months_late", nullable = false, updatable = false)
    private Integer monthsLate;

    @Column(name = "penalty_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal penaltyPct;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}
