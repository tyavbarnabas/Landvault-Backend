package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonWriter;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplacePriceTierDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceVerificationCheckDto;
import com.techcomfort.landvaultbackend.marketplace.dto.SellerDto;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.AmenityView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.ListingView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PlotView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PriceTierView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.VerificationCheckView;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.AmenityViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewSpecifications;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PlotViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PriceTierViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.VerificationCheckViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The public read side. Anonymous callers, no scope, no audit: the audit log
 * records actions, not page views, and logging browsing would drown the
 * entries that matter.
 * <p>
 * Everything here reads a {@code marketplace_*} view and nothing else.
 * <strong>Never add a tenant-table repository to this class</strong>: under
 * an anonymous request it would silently return nothing (RLS fails closed),
 * and the obvious "fix", elevating scope, would be the leak MP-3 exists to
 * prevent. If a buyer needs a new field, it goes in the view, where it is
 * reviewed as a publication decision.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceQueryService {

    /** The frontend's tiersFromPlots() rule, so both sides label stock the same way. */
    private static final int LOW_STOCK_THRESHOLD = 3;

    private final ListingViewRepository listings;
    private final PriceTierViewRepository tiers;
    private final PlotViewRepository plots;
    private final VerificationCheckViewRepository checks;
    private final AmenityViewRepository amenities;

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceListingDto> search(ListingViewSpecifications.Filters filters, Pageable pageable) {
        Page<ListingView> page = listings.findAll(ListingViewSpecifications.matching(filters), pageable);
        return PageResponses.from(page, toDtos(page.getContent())::get);
    }

    @Transactional(readOnly = true)
    public MarketplaceListingDto get(UUID estateId) {
        ListingView listing = listings.findById(estateId).orElseThrow(ListingNotFound::new);
        return toDtos(List.of(listing)).get(listing);
    }

    /**
     * The estate and its plots as a GeoJSON FeatureCollection: the same
     * types the portal's map uses, so one frontend component renders both.
     * Plot properties carry the collapsed availability only, and no price:
     * the client computes a plot's price from its tier and the estate's
     * corner premium, exactly as the frontend's priceForPlot() already does.
     */
    @Transactional(readOnly = true)
    public GeoJsonFeatureCollectionDto geoJson(UUID estateId) {
        ListingView listing = listings.findById(estateId).orElseThrow(ListingNotFound::new);

        List<GeoJsonFeatureDto> features = new ArrayList<>();
        if (listing.getFootprint() != null) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", "estate");
            properties.put("id", listing.getEstateId());
            properties.put("name", listing.getName());
            features.add(GeoJsonFeatureDto.of(GeoJsonPolygonWriter.toGeoJson(listing.getFootprint()), properties));
        }
        for (PlotView plot : plots.findByEstateIdAndFootprintIsNotNull(estateId)) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", "plot");
            properties.put("id", plot.getPlotId());
            properties.put("plotNumber", plot.getPlotNumber());
            properties.put("blockName", plot.getBlockName());
            properties.put("availability", plot.getAvailability());
            properties.put("isCorner", plot.isCorner());
            properties.put("priceTierId", plot.getPriceTierId());
            properties.put("nominalSizeSqm", plot.getNominalSizeSqm());
            properties.put("actualAreaSqm", plot.getActualAreaSqm());
            features.add(GeoJsonFeatureDto.of(GeoJsonPolygonWriter.toGeoJson(plot.getFootprint()), properties));
        }
        return GeoJsonFeatureCollectionDto.of(features);
    }

    /** Tiers, checks and amenities for a whole page in three queries, never one per listing. */
    private Map<ListingView, MarketplaceListingDto> toDtos(List<ListingView> page) {
        List<UUID> ids = page.stream().map(ListingView::getEstateId).toList();
        Map<UUID, List<PriceTierView>> tiersByEstate = ids.isEmpty() ? Map.of()
                : tiers.findByEstateIdInOrderByPriceAsc(ids).stream()
                        .collect(Collectors.groupingBy(PriceTierView::getEstateId));
        Map<UUID, List<VerificationCheckView>> checksByEstate = ids.isEmpty() ? Map.of()
                : checks.findByEstateIdIn(ids).stream()
                        .collect(Collectors.groupingBy(VerificationCheckView::getEstateId));
        Map<UUID, List<String>> amenitiesByEstate = ids.isEmpty() ? Map.of()
                : amenities.findByEstateIdInOrderByNameAsc(ids).stream()
                        .collect(Collectors.groupingBy(AmenityView::getEstateId,
                                Collectors.mapping(AmenityView::getName, Collectors.toList())));

        Map<ListingView, MarketplaceListingDto> result = new LinkedHashMap<>();
        for (ListingView l : page) {
            result.put(l, new MarketplaceListingDto(
                    l.getEstateId(),
                    l.getName(),
                    l.getArea(),
                    l.getCity(),
                    l.getState(),
                    l.getDescription(),
                    amenitiesByEstate.getOrDefault(l.getEstateId(), List.of()),
                    l.getImageUrl(),
                    l.getTitleType() == null ? null : l.getTitleType().getValue(),
                    l.getLastVerifiedAt(),
                    tiersByEstate.getOrDefault(l.getEstateId(), List.of()).stream()
                            .map(MarketplaceQueryService::toDto).toList(),
                    l.getCornerPremiumPct(),
                    l.getIntent() == null ? null : l.getIntent().getValue(),
                    l.getPublishedAt(),
                    new SellerDto(l.getBranchName(), l.getCompanyName()),
                    true,
                    l.getFromPrice(),
                    l.getFromPriceCurrency(),
                    l.getPlotsRemaining(),
                    l.getFootprint() != null,
                    checksByEstate.getOrDefault(l.getEstateId(), List.of()).stream()
                            .map(MarketplaceQueryService::toDto).toList()));
        }
        return result;
    }

    private static MarketplacePriceTierDto toDto(PriceTierView t) {
        String availability = t.getPlotsRemaining() == 0 ? "sold_out"
                : t.getPlotsRemaining() <= LOW_STOCK_THRESHOLD ? "low_stock" : "available";
        return new MarketplacePriceTierDto(t.getTierId(), t.getSizeSqm(), t.getActualAreaSqm(), t.getLabel(),
                t.getPrice(), t.getCurrency(), t.getPricePerSqm(), availability, t.getPlotsRemaining());
    }

    private static MarketplaceVerificationCheckDto toDto(VerificationCheckView c) {
        return new MarketplaceVerificationCheckDto(
                c.getCheckType().getValue(),
                c.getStatus().getValue(),
                c.getVerificationSource() == null ? null : c.getVerificationSource().getValue(),
                c.getLastVerifiedAt());
    }

    /**
     * Not published, not eligible, or not an estate at all: deliberately one
     * answer. Telling a stranger "this exists but is hidden" would reveal a
     * suspended company's inventory or a contested estate's existence.
     */
    public static class ListingNotFound extends NoSuchElementException {
    }
}
