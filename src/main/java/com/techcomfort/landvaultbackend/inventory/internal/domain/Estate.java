package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.EstateIntent;
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
import java.time.Instant;

/**
 * A land development owned by a tenant's branch. Unlike {@code Organization}
 * and {@code Branch}, an estate populates the inherited {@code tenantId} and
 * {@code branchId} — branch ownership is what makes branch-scoped RLS
 * meaningful here (a Double King manager sees Double King's estates, never
 * Heritage's).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "estates",
        indexes = {
                @Index(name = "idx_estates_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_estates_branch_id", columnList = "branch_id"),
                @Index(name = "idx_estates_state", columnList = "state"),
                @Index(name = "idx_estates_published", columnList = "published")
        },
        uniqueConstraints = @UniqueConstraint(name = "uq_estates_tenant_slug", columnNames = {"tenant_id", "slug"})
)
@SQLRestriction("deleted = false")
public class Estate extends AbstractEntity {

    @Column(name = "name", nullable = false)
    private String name;

    /** Unique per tenant, not globally — two developers may both have a "Palm Grove". */
    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "area")
    private String area;

    @Column(name = "city")
    private String city;

    /** A Nigerian state name, matching the frontend's {@code NigerianState} list. */
    @Column(name = "state", length = 64)
    private String state;

    @Column(name = "address")
    private String address;

    /**
     * The estate's real boundary, SRID 4326 (WGS84 lat/lng), GiST-indexed.
     * Nullable: an estate exists as a draft before its boundary is uploaded.
     * <p>
     * Area is computed via {@code ST_Area(footprint::geography)} for square
     * metres — {@code ST_Area} on raw 4326 returns square <em>degrees</em>,
     * which is not an error, just a plausible-looking wrong number. See
     * AGENTS.md.
     */
    @Column(name = "footprint", columnDefinition = "geometry(Polygon,4326)")
    private Polygon footprint;

    /**
     * How much more a corner plot costs, as a percentage of its tier's price.
     * <p>
     * <strong>A modifier, never a separate tier.</strong> A corner plot's
     * price is computed as {@code tierPrice × (1 + cornerPremiumPct / 100)};
     * storing it as its own price would create a second figure that can drift
     * from the tier it is derived from.
     */
    @Column(name = "corner_premium_pct", precision = 5, scale = 2)
    private BigDecimal cornerPremiumPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 32)
    private EstateIntent intent;

    /**
     * The developer's own opt-in switch for the public marketplace — one of
     * four conditions, not the whole gate.
     * <p>
     * An estate is publicly listable only when ALL of: this is true; the
     * owning tenant's {@code verificationState} is {@code VERIFIED}; that
     * tenant holds the {@code marketplacePublishing} entitlement; and the
     * tenant's {@code status} is not {@code SUSPENDED}. That check belongs to
     * the {@code marketplace} projection, deliberately not to this schema —
     * see AGENTS.md so it isn't reinvented or half-implemented there.
     */
    @Column(name = "published", nullable = false)
    private Boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "image_url")
    private String imageUrl;

    @Override
    public void prePersist() {
        super.prePersist();
        if (published == null) {
            published = false;
        }
    }
}
