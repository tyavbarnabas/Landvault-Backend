package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.common.PlanKind;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
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

    /** Instalment plans only; null on a flexible plan. */
    @Column(name = "instalment_count", updatable = false)
    private Integer instalmentCount;

    /** Instalment plans only; null on a flexible plan. */
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", updatable = false, length = 16)
    private PaymentFrequency frequency;

    @Column(name = "premium_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal premiumPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private PlanKind kind;

    // Flexible plans only (changeset 110); null on an instalment plan. The minimum to start is depositPct.

    @Column(name = "minimum_contribution", updatable = false, precision = 19, scale = 4)
    private BigDecimal minimumContribution;

    @Column(name = "exit_deduction_pct", updatable = false, precision = 5, scale = 2)
    private BigDecimal exitDeductionPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_applies_to", updatable = false, length = 16)
    private RefundAppliesTo exitAppliesTo;

    @Column(name = "developer_gain_share_pct", updatable = false, precision = 5, scale = 2)
    private BigDecimal developerGainSharePct;

    @Column(name = "inactivity_months", updatable = false)
    private Integer inactivityMonths;

    @Column(name = "notice_days", updatable = false)
    private Integer noticeDays;

    @Column(name = "max_months", updatable = false)
    private Integer maxMonths;

    public FlexiblePlanCalculator.Terms flexibleTerms() {
        return new FlexiblePlanCalculator.Terms(depositPct, premiumPct, minimumContribution, exitDeductionPct,
                exitAppliesTo, developerGainSharePct, inactivityMonths, noticeDays, maxMonths);
    }
}
