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
 * A row of {@code marketplace_resale_listings} (changeset 117): one plot a
 * buyer is selling, on an estate on the marketplace. Nothing here says who
 * the seller is, what they paid, or whether it is an early sale.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_resale_listings")
public class ResaleListingView {

    @Id
    @Column(name = "listing_id")
    private UUID listingId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "plot_id")
    private UUID plotId;

    @Column(name = "estate_name")
    private String estateName;

    @Column(name = "area")
    private String area;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent")
    private EstateIntent intent;

    @Column(name = "cover_file_id")
    private UUID coverFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_type")
    private TitleType titleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_status")
    private TitleStatus titleStatus;

    @Column(name = "title_verified_at")
    private Instant titleVerifiedAt;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "block_name")
    private String blockName;

    @Column(name = "plot_number")
    private String plotNumber;

    @Column(name = "is_corner")
    private boolean corner;

    @Column(name = "nominal_size_sqm")
    private BigDecimal nominalSizeSqm;

    @Column(name = "actual_area_sqm")
    private BigDecimal actualAreaSqm;

    @Column(name = "has_boundary")
    private boolean hasBoundary;

    @Column(name = "price")
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency")
    private Currency currency;

    @Column(name = "price_per_sqm")
    private BigDecimal pricePerSqm;

    @Column(name = "listed_at")
    private Instant listedAt;

    /** Null when the estate declares no default terms. */
    @Column(name = "transfer_requires_consent")
    private Boolean transferRequiresConsent;
}
