package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code marketplace_plots}. {@code availability} is already
 * collapsed to AVAILABLE/UNAVAILABLE inside the view: the raw plot status
 * never reaches this class, so it cannot reach a buyer.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_plots")
public class PlotView {

    @Id
    @Column(name = "plot_id")
    private UUID plotId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "price_tier_id")
    private UUID priceTierId;

    @Column(name = "plot_number")
    private String plotNumber;

    @Column(name = "block_name")
    private String blockName;

    @Column(name = "is_corner")
    private boolean corner;

    @Column(name = "nominal_size_sqm")
    private BigDecimal nominalSizeSqm;

    @Column(name = "actual_area_sqm")
    private BigDecimal actualAreaSqm;

    @Column(name = "availability", columnDefinition = "text")
    private String availability;

    @Column(name = "footprint", columnDefinition = "geometry(Polygon,4326)")
    private Polygon footprint;
}
