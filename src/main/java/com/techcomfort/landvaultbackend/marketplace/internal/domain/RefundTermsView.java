package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/** A row of {@code marketplace_refund_terms} — the current policy, keyed by estate. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_refund_terms")
public class RefundTermsView {

    @Id
    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "deduction_pct")
    private BigDecimal deductionPct;

    @Column(name = "processing_days")
    private int processingDays;

    @Column(name = "applies_to")
    private String appliesTo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "non_refundable_fee_types")
    private String[] nonRefundableFeeTypes;

    @Column(name = "notes")
    private String notes;
}
