package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One instalment plan an estate offers (PL-1), part of a versioned set like
 * {@link EstateFee}. Terms only — HomeTrue works out every figure from them.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_payment_plans")
@SQLRestriction("deleted = false")
public class EstatePaymentPlan extends AbstractEntity {

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private Integer sortOrder;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    /** A share of the plan total, premium included; null when {@link #depositAmount} is set. */
    @Column(name = "deposit_pct", updatable = false, precision = 5, scale = 2)
    private BigDecimal depositPct;

    @Column(name = "deposit_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal depositAmount;

    @Column(name = "instalment_count", nullable = false, updatable = false)
    private Integer instalmentCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, updatable = false, length = 16)
    private PaymentFrequency frequency;

    @Column(name = "premium_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal premiumPct;
}
