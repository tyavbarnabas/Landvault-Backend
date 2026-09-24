package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a buyer gets back if they withdraw, and how long it takes. Versioned;
 * the current policy is the highest version. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_refund_terms")
@SQLRestriction("deleted = false")
public class EstateRefundTerms extends AbstractEntity {

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @Column(name = "deduction_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal deductionPct;

    /** Both source letters: 90. Time is part of the cost. */
    @Column(name = "processing_days", nullable = false)
    private Integer processingDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 16)
    private RefundAppliesTo appliesTo;

    /**
     * Fee types never returned. Stored as the enum's Java constant names in
     * a Postgres array — a small, fixed set read only alongside its parent
     * row, so a child table would be machinery without a purpose.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "non_refundable_fee_types", columnDefinition = "varchar(32)[]")
    private String[] nonRefundableFeeTypes;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;
}
