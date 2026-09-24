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

/** A row of {@code marketplace_penalty_tiers}. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_penalty_tiers")
public class PenaltyTierView {

    @Id
    @Column(name = "penalty_tier_id")
    private UUID penaltyTierId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "months_late")
    private int monthsLate;

    @Column(name = "penalty_pct")
    private BigDecimal penaltyPct;
}
