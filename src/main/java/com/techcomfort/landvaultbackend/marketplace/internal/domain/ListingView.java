package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.common.TitleType;
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
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code marketplace_listings}: one eligible estate. Every field
 * here is something a buyer may see; the view is where that list is decided.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_listings")
public class ListingView {

    @Id
    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "name")
    private String name;

    @Column(name = "area")
    private String area;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "image_url")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent")
    private EstateIntent intent;

    @Column(name = "corner_premium_pct")
    private BigDecimal cornerPremiumPct;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "footprint", columnDefinition = "geometry(Polygon,4326)")
    private Polygon footprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_type")
    private TitleType titleType;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "from_price")
    private BigDecimal fromPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_price_currency")
    private Currency fromPriceCurrency;

    @Column(name = "from_price_per_sqm")
    private BigDecimal fromPricePerSqm;

    @Column(name = "min_size_sqm")
    private BigDecimal minSizeSqm;

    @Column(name = "max_size_sqm")
    private BigDecimal maxSizeSqm;

    @Column(name = "plots_remaining")
    private long plotsRemaining;
}
