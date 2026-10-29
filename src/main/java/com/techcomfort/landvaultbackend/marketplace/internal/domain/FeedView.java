package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.common.TitleStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code marketplace_feed} (changeset 117): an estate or a resale
 * plot, with just what the feed filters and sorts on. The item itself is
 * built from its own view.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_feed")
public class FeedView {

    /** PRIMARY (an estate) or RESALE (a plot a buyer is selling). */
    @Column(name = "listing_type")
    private String listingType;

    @Id
    @Column(name = "listing_id")
    private UUID listingId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "intent")
    private EstateIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_type")
    private TitleType titleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_status")
    private TitleStatus titleStatus;

    @Column(name = "price")
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency")
    private Currency currency;

    @Column(name = "price_per_sqm")
    private BigDecimal pricePerSqm;

    @Column(name = "min_size_sqm")
    private BigDecimal minSizeSqm;

    @Column(name = "max_size_sqm")
    private BigDecimal maxSizeSqm;

    @Column(name = "plots_remaining")
    private long plotsRemaining;

    @Column(name = "listed_at")
    private Instant listedAt;
}
