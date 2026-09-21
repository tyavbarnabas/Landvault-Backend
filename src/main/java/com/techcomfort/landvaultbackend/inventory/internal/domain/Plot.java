package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotOrientation;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
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
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The sellable unit. Its price comes from its {@link PriceTier}, plus the
 * estate's corner premium when {@link #isCorner} — never stored here, so the
 * two can never disagree.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "plots",
        indexes = {
                @Index(name = "idx_plots_estate_id", columnList = "estate_id"),
                @Index(name = "idx_plots_block_id", columnList = "block_id"),
                @Index(name = "idx_plots_price_tier_id", columnList = "price_tier_id"),
                @Index(name = "idx_plots_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_plots_branch_id", columnList = "branch_id"),
                @Index(name = "idx_plots_status", columnList = "status")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_plots_estate_block_number", columnNames = {"estate_id", "block_id", "plot_number"})
)
@SQLRestriction("deleted = false")
public class Plot extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    /** Nullable — not every estate is subdivided into blocks. */
    @Column(name = "block_id")
    private UUID blockId;

    @Column(name = "price_tier_id", nullable = false)
    private UUID priceTierId;

    @Column(name = "plot_number", nullable = false, length = 32)
    private String plotNumber;

    /**
     * Whether this plot is a corner. With {@code Estate.cornerPremiumPct}
     * this makes the price computable — which is why no corner price is
     * stored anywhere.
     */
    @Column(name = "is_corner", nullable = false)
    private Boolean isCorner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PlotStatus status;

    /** Nullable — a plot inherits the estate's intent unless sold specifically as one or the other. */
    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 32)
    private PlotIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "orientation", length = 2)
    private PlotOrientation orientation;

    /**
     * What this plot is <strong>sold and priced as</strong> — the number on
     * the deed and the invoice. Comes from its tier.
     * <p>
     * Deliberately stored alongside {@link #actualAreaSqm} rather than
     * instead of it: a plot sold as "250 sqm" may survey at 248.6, and
     * keeping only one of the two loses either the commercial truth or the
     * physical truth. Price off this; display the surveyed figure separately.
     */
    @Column(name = "nominal_size_sqm", nullable = false, precision = 12, scale = 2)
    private BigDecimal nominalSizeSqm;

    /**
     * What the survey says, derived from {@link #footprint} via
     * {@code ST_Area(footprint::geography)} (square metres — a geography cast,
     * never raw 4326, which yields square degrees).
     * <p>
     * <strong>Nullable, and must never default to {@link #nominalSizeSqm}.</strong>
     * A plot with no footprint has no surveyed area; absent means absent, and
     * copying the nominal value here would manufacture a survey result that
     * nobody produced.
     */
    @Column(name = "actual_area_sqm", precision = 12, scale = 2)
    private BigDecimal actualAreaSqm;

    /**
     * This plot's own boundary, SRID 4326, GiST-indexed. Nullable while an
     * estate is still a draft.
     * <p>
     * Not optional in the long run: the within-estate double allocation — the
     * same plot sold twice inside one estate — is the more common scam and is
     * currently undetectable precisely <em>because</em> plots have no
     * geometry. This column is what makes real {@code ST_Intersects}
     * detection possible rather than a bounding-box heuristic. See AGENTS.md.
     */
    @Column(name = "footprint", columnDefinition = "geometry(Polygon,4326)")
    private Polygon footprint;

    @Override
    public void prePersist() {
        super.prePersist();
        if (isCorner == null) {
            isCorner = false;
        }
    }
}
