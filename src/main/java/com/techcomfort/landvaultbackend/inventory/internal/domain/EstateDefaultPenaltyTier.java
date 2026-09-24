package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One rung of a late-payment escalation — Top Rank's is 5% at three months,
 * 10% at six, 20% at twelve. A child of its versioned parent, so a schedule
 * cannot be edited out from under whoever was shown it.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_default_penalty_tiers")
@SQLRestriction("deleted = false")
public class EstateDefaultPenaltyTier extends AbstractEntity {

    @Column(name = "default_terms_id", nullable = false, updatable = false)
    private UUID defaultTermsId;

    @Column(name = "months_late", nullable = false)
    private Integer monthsLate;

    @Column(name = "penalty_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal penaltyPct;
}
