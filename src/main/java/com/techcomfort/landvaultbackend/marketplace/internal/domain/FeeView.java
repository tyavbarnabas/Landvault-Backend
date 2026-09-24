package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code marketplace_estate_fees} — the current declaration only,
 * for an estate that currently qualifies. Read-only: the view is the
 * publication decision, and widening it is what publishes a new field.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_estate_fees")
public class FeeView {

    @Id
    @Column(name = "fee_id")
    private UUID feeId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "fee_type")
    private String feeType;

    @Column(name = "label")
    private String label;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "amount_min")
    private BigDecimal amountMin;

    @Column(name = "amount_max")
    private BigDecimal amountMax;

    @Column(name = "currency")
    private String currency;

    @Column(name = "is_fixed")
    private boolean fixed;

    @Column(name = "variation_basis")
    private String variationBasis;

    @Column(name = "due_trigger")
    private String dueTrigger;

    @Column(name = "refundable")
    private boolean refundable;

    @Column(name = "is_mandatory")
    private boolean mandatory;

    @Column(name = "notes")
    private String notes;

    /** The fixed amount, or the range's floor. Never averaged with {@link #high()}. */
    public BigDecimal low() {
        return fixed ? amount : amountMin;
    }

    public BigDecimal high() {
        return fixed ? amount : amountMax;
    }
}
