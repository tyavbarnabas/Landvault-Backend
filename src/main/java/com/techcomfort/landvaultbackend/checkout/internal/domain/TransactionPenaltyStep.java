package com.techcomfort.landvaultbackend.checkout.internal.domain;

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

/** One late-payment step a purchase was offered (changeset 104). Copied at Proceed to buy; never changed. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "transaction_penalty_steps")
public class TransactionPenaltyStep extends AbstractAppendOnlyEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "months_late", nullable = false, updatable = false)
    private Integer monthsLate;

    @Column(name = "penalty_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal penaltyPct;
}
