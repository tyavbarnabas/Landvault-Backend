package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
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
 * One declared charge beyond the land price. Versioned — a revision writes a
 * new version rather than editing this row. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_fees")
@SQLRestriction("deleted = false")
public class EstateFee extends AbstractEntity {

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    /** Which declaration this belongs to; the current one is the estate's {@code feesVersion}. */
    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false, length = 32)
    private FeeType feeType;

    /** Required for {@link FeeType#OTHER}: an unnamed charge is not a disclosure. */
    @Column(name = "label", length = 128)
    private String label;

    /** Set only for a fixed fee; a variable one carries the range instead. */
    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "amount_min", precision = 19, scale = 4)
    private BigDecimal amountMin;

    @Column(name = "amount_max", precision = 19, scale = 4)
    private BigDecimal amountMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Column(name = "is_fixed", nullable = false)
    private Boolean isFixed;

    /**
     * Why a variable fee varies, in the developer's own words — required
     * whenever the fee is not fixed. Both source letters cite fluctuating
     * building-material prices, which is a real constraint rather than
     * necessarily a trick. Silence is what is forbidden.
     */
    @Column(name = "variation_basis", columnDefinition = "text")
    private String variationBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "due_trigger", nullable = false, length = 32)
    private DueTrigger dueTrigger;

    @Column(name = "refundable", nullable = false)
    private Boolean refundable;

    /**
     * A fee whose stated condition is itself mandatory is mandatory — see
     * the column comment in changeset 056.
     */
    @Column(name = "is_mandatory", nullable = false)
    private Boolean isMandatory;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    /** The low end of what this costs: the fixed amount, or the range's floor. */
    public BigDecimal effectiveMin() {
        return Boolean.TRUE.equals(isFixed) ? amount : amountMin;
    }

    /** The high end. Never averaged with {@link #effectiveMin()} — a midpoint is a figure nobody quoted. */
    public BigDecimal effectiveMax() {
        return Boolean.TRUE.equals(isFixed) ? amount : amountMax;
    }
}
