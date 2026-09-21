package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.inventory.internal.enums.ListingIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotOrientation;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PropertyType;
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

    /**
     * Bare land, or a plot carrying a building. {@code BUILT} means this plot
     * has units — the hierarchy extends rather than forks
     * ({@code Estate → Block → Plot → Unit}), so land stays the base and
     * geometry stays here at plot level. No {@code units} table exists yet;
     * this makes one possible without committing to its shape.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 16)
    private PropertyType propertyType;

    /**
     * What the seller is offering — sale, rent, or either. The <em>only</em>
     * column rentals need in this module: everything else a rental requires
     * (recurring rent, agreements, deposits, renewals) is a transaction
     * concern owned by {@code sales}/{@code finance}/{@code documents}.
     * <p>
     * Distinct from {@link #intent} — see {@link ListingIntent}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "listing_intent", nullable = false, length = 16)
    private ListingIntent listingIntent;

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
     * <p>
     * Copied from the tier at creation, <strong>never accepted from the
     * request</strong> — a caller-supplied size could contradict the tier the
     * plot is priced by, leaving the invoice and the deed disagreeing.
     * <p>
     * Nullable only for {@code UNIT_TYPE} tiers: an apartment has no
     * exclusive land area, and writing a zero there would fabricate a figure.
     * A {@code LAND_SIZE} plot always has one; that half is enforced in the
     * service, because a row-level CHECK cannot see the referenced tier's
     * type.
     */
    @Column(name = "nominal_size_sqm", precision = 12, scale = 2)
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
        // Mirrors the column defaults — every existing row is bare land
        // offered for sale, which is what the backfill assumes too.
        if (propertyType == null) {
            propertyType = PropertyType.LAND;
        }
        if (listingIntent == null) {
            listingIntent = ListingIntent.FOR_SALE;
        }
    }
}
