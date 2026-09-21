package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A size band an estate's plots actually come in, with the developer's own
 * price for that band.
 * <p>
 * Corner plots are deliberately <em>not</em> a tier — a corner is a per-plot
 * modifier on its tier's price (see {@code Estate.cornerPremiumPct}), not its
 * own menu item.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "price_tiers",
        indexes = {
                @Index(name = "idx_price_tiers_estate_id", columnList = "estate_id"),
                @Index(name = "idx_price_tiers_tenant_id", columnList = "tenant_id")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_price_tiers_estate_size", columnNames = {"estate_id", "size_sqm"})
)
@SQLRestriction("deleted = false")
public class PriceTier extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    /**
     * The nominal size this tier represents — what plots in it are sold and
     * priced as, not what any of them survey at. See {@code Plot}.
     */
    @Column(name = "size_sqm", nullable = false, precision = 12, scale = 2)
    private BigDecimal sizeSqm;

    /**
     * This tier's own developer-set price.
     * <p>
     * <strong>Never derived from a single per-sqm rate.</strong> Larger plots
     * are routinely discounted per square metre — 180 sqm might sell at
     * ₦18,000/sqm while 600 sqm sells at ₦14,500/sqm — so a per-sqm figure is
     * a <em>displayed comparison</em> computed for the UI, never an input to
     * pricing. Deriving price from a rate would quietly overcharge every
     * large plot.
     * <p>
     * {@code numeric}/{@link BigDecimal}, never floating point.
     */
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    /**
     * The currency {@link #price} is genuinely denominated in — never dropped
     * and never silently converted. See {@code common.Currency}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    /** Optional display name, e.g. "Standard 250". */
    @Column(name = "label")
    private String label;
}
