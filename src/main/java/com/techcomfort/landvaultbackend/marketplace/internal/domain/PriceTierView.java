package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import com.techcomfort.landvaultbackend.common.Currency;
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

/** A row of {@code marketplace_price_tiers}. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_price_tiers")
public class PriceTierView {

    @Id
    @Column(name = "tier_id")
    private UUID tierId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "size_sqm")
    private BigDecimal sizeSqm;

    @Column(name = "label")
    private String label;

    @Column(name = "price")
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency")
    private Currency currency;

    /** A display comparison, null for a UNIT_TYPE tier. Computed in the view. */
    @Column(name = "price_per_sqm")
    private BigDecimal pricePerSqm;

    @Column(name = "plots_remaining")
    private long plotsRemaining;

    @Column(name = "actual_area_sqm")
    private BigDecimal actualAreaSqm;
}
