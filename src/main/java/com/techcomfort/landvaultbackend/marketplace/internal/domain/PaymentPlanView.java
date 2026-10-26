package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.common.PlanKind;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/** An eligible estate's current instalment plan, as published (changeset 099). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_payment_plans")
public class PaymentPlanView {

    @Id
    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "name")
    private String name;

    @Column(name = "deposit_pct")
    private BigDecimal depositPct;

    @Column(name = "deposit_amount")
    private BigDecimal depositAmount;

    @Column(name = "instalment_count")
    private Integer instalmentCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency")
    private PaymentFrequency frequency;

    @Column(name = "premium_pct")
    private BigDecimal premiumPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private PlanKind kind;

    @Column(name = "minimum_contribution")
    private BigDecimal minimumContribution;

    @Column(name = "exit_deduction_pct")
    private BigDecimal exitDeductionPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_applies_to")
    private RefundAppliesTo exitAppliesTo;

    @Column(name = "developer_gain_share_pct")
    private BigDecimal developerGainSharePct;

    @Column(name = "inactivity_months")
    private Integer inactivityMonths;

    @Column(name = "notice_days")
    private Integer noticeDays;

    @Column(name = "max_months")
    private Integer maxMonths;

    /** An instalment plan's terms; never called on a flexible plan, which has no count or frequency. */
    public InstalmentPlanCalculator.Terms terms() {
        return new InstalmentPlanCalculator.Terms(depositPct, depositAmount, instalmentCount, frequency, premiumPct);
    }

    public FlexiblePlanCalculator.Terms flexibleTerms() {
        return new FlexiblePlanCalculator.Terms(depositPct, premiumPct, minimumContribution, exitDeductionPct,
                exitAppliesTo, developerGainSharePct, inactivityMonths, noticeDays, maxMonths);
    }
}
