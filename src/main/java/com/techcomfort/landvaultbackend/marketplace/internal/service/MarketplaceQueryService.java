package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.common.CommitmentCalculator;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.PlanKind;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonWriter;
import com.techcomfort.landvaultbackend.marketplace.dto.BranchOfficeDto;
import com.techcomfort.landvaultbackend.marketplace.dto.CostDisclosureDto;
import com.techcomfort.landvaultbackend.marketplace.dto.ExitCostsDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceFeedItemDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceResaleListingDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplacePaymentPlanDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplacePhotoDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplacePlotDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplacePriceTierDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceVerificationCheckDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MoneyRangeDto;
import com.techcomfort.landvaultbackend.marketplace.dto.PublicFeeDto;
import com.techcomfort.landvaultbackend.marketplace.dto.SellerDto;
import com.techcomfort.landvaultbackend.marketplace.dto.TierCommitmentDto;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.AmenityView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.DefaultTermsView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.EstatePhotoView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.FeeView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.FeedView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.ListingView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PaymentPlanView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PenaltyTierView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PlotView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PriceTierView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.RefundTermsView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.ResaleListingView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.VerificationCheckView;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.AmenityViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.DefaultTermsViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.EstatePhotoViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.FeeViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.FeedViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.FeedViewSpecifications;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewSpecifications;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PaymentPlanViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PenaltyTierViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PlotViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PriceTierViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.RefundTermsViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ResaleListingViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.VerificationCheckViewRepository;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** marketplace_feed's listing_type values. */
    private static final String PRIMARY = "PRIMARY";
    private static final String RESALE = "RESALE";

    private final ListingViewRepository listings;
    private final PriceTierViewRepository tiers;
    private final PlotViewRepository plots;
    private final VerificationCheckViewRepository checks;
    private final AmenityViewRepository amenities;
    private final FeeViewRepository fees;
    private final RefundTermsViewRepository refundTerms;
    private final DefaultTermsViewRepository defaultTerms;
    private final PenaltyTierViewRepository penaltyTiers;
    private final PaymentPlanViewRepository paymentPlans;
    private final EstatePhotoViewRepository photos;
    private final ResaleListingViewRepository resales;
    private final FeedViewRepository feed;
    private final StorageApi storage;

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
     * Sell my plot, phase 3: estates and resale plots as one list, sorted and
     * paged once across both (marketplace_feed), each item then built from
     * its own view in batches — never one query per item.
     */
    @Transactional(readOnly = true)
    public PageResponse<MarketplaceFeedItemDto> feed(ListingViewSpecifications.Filters filters, String listingType,
                                                     UUID estateId, Pageable pageable) {
        Page<FeedView> page = feed.findAll(FeedViewSpecifications.matching(filters, listingType, estateId), pageable);
        List<UUID> estateIds = page.getContent().stream().filter(f -> PRIMARY.equals(f.getListingType()))
                .map(FeedView::getListingId).toList();
        List<UUID> resaleIds = page.getContent().stream().filter(f -> RESALE.equals(f.getListingType()))
                .map(FeedView::getListingId).toList();
        Map<UUID, MarketplaceListingDto> estates = new HashMap<>();
        if (!estateIds.isEmpty()) {
            toDtos(listings.findByEstateIdIn(estateIds)).values().forEach(d -> estates.put(d.id(), d));
        }
        Map<UUID, MarketplaceResaleListingDto> resaleDtos = new HashMap<>();
        if (!resaleIds.isEmpty()) {
            resaleDtos(resales.findByListingIdIn(resaleIds)).forEach(d -> resaleDtos.put(d.id(), d));
        }
        // A row that left its view between the two reads (an estate unlisted
        // that instant) is dropped rather than returned half-built.
        List<MarketplaceFeedItemDto> items = page.getContent().stream()
                .map(f -> PRIMARY.equals(f.getListingType())
                        ? (estates.containsKey(f.getListingId())
                                ? new MarketplaceFeedItemDto("primary", estates.get(f.getListingId())) : null)
                        : (resaleDtos.containsKey(f.getListingId())
                                ? new MarketplaceFeedItemDto("resale", resaleDtos.get(f.getListingId())) : null))
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, page.getTotalElements(), page.hasNext() ? String.valueOf(page.getNumber() + 1)
                : null, page.hasNext());
    }

    /** One plot for resale; 404 when it isn't on the marketplace, for whatever reason. */
    @Transactional(readOnly = true)
    public MarketplaceResaleListingDto resale(UUID listingId) {
        return resales.findById(listingId).map(r -> resaleDtos(List.of(r)).getFirst())
                .orElseThrow(ListingNotFound::new);
    }

    private List<MarketplaceResaleListingDto> resaleDtos(List<ResaleListingView> rows) {
        Set<UUID> fileIds = rows.stream().map(ResaleListingView::getCoverFileId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> urls = new HashMap<>();
        storage.findAll(fileIds).forEach(f -> {
            if (f.publicUrl() != null) {
                urls.put(f.id(), f.publicUrl());
            }
        });
        return rows.stream().map(r -> new MarketplaceResaleListingDto(
                r.getListingId(), r.getPlotId(), r.getEstateId(), r.getEstateName(),
                r.getBlockName() == null ? "Plot " + r.getPlotNumber()
                        : "Block " + r.getBlockName() + ", Plot " + r.getPlotNumber(),
                r.getBlockName(), r.getPlotNumber(), r.getArea(), r.getCity(), r.getState(),
                r.getIntent() == null ? null : r.getIntent().getValue(),
                r.getCoverFileId() == null ? null : urls.get(r.getCoverFileId()),
                r.getTitleType() == null ? null : r.getTitleType().getValue(),
                r.getTitleStatus() == null ? null : r.getTitleStatus().getValue(),
                r.getTitleVerifiedAt(), r.getCompanyName(), r.isCorner(), r.getNominalSizeSqm(),
                r.getActualAreaSqm(), r.isHasBoundary(), r.getPrice(), r.getCurrency(), r.getPricePerSqm(),
                r.getListedAt(), r.getTransferRequiresConsent())).toList();
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

    /**
     * Every plot on a published estate, including those with no surveyed
     * boundary — the map can't draw them, but they are for sale and a buyer
     * must be able to choose them, knowing the boundary is missing.
     * Availability stays collapsed, exactly as on the map.
     */
    @Transactional(readOnly = true)
    public PageResponse<MarketplacePlotDto> plots(UUID estateId, UUID priceTierId, boolean availableOnly, Pageable pageable) {
        listings.findById(estateId).orElseThrow(ListingNotFound::new);
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.asc("blockName").nullsFirst(), Sort.Order.asc("plotNumber")));
        Page<PlotView> page;
        if (priceTierId != null) {
            page = availableOnly
                    ? plots.findByEstateIdAndPriceTierIdAndAvailability(estateId, priceTierId, "AVAILABLE", ordered)
                    : plots.findByEstateIdAndPriceTierId(estateId, priceTierId, ordered);
        } else {
            page = availableOnly
                    ? plots.findByEstateIdAndAvailability(estateId, "AVAILABLE", ordered)
                    : plots.findByEstateId(estateId, ordered);
        }
        return PageResponses.from(page, p -> new MarketplacePlotDto(p.getPlotId(), p.getPlotNumber(),
                p.getBlockName(), p.getAvailability(), p.isCorner(), p.getPriceTierId(), p.getNominalSizeSqm(),
                p.getActualAreaSqm(), p.getFootprint() != null));
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

        // Four more batched queries, never one per listing — the same rule
        // the tiers/checks/amenities loads above already follow.
        Map<UUID, List<FeeView>> feesByEstate = ids.isEmpty() ? Map.of()
                : fees.findByEstateIdIn(ids).stream()
                        .collect(Collectors.groupingBy(FeeView::getEstateId));
        Map<UUID, RefundTermsView> refundByEstate = ids.isEmpty() ? Map.of()
                : refundTerms.findByEstateIdIn(ids).stream()
                        .collect(Collectors.toMap(RefundTermsView::getEstateId, view -> view));
        Map<UUID, DefaultTermsView> defaultsByEstate = ids.isEmpty() ? Map.of()
                : defaultTerms.findByEstateIdIn(ids).stream()
                        .collect(Collectors.toMap(DefaultTermsView::getEstateId, view -> view));
        Map<UUID, List<PenaltyTierView>> penaltiesByEstate = ids.isEmpty() ? Map.of()
                : penaltyTiers.findByEstateIdInOrderByMonthsLateAsc(ids).stream()
                        .collect(Collectors.groupingBy(PenaltyTierView::getEstateId));
        Map<UUID, List<PaymentPlanView>> plansByEstate = ids.isEmpty() ? Map.of()
                : paymentPlans.findByEstateIdInOrderBySortOrderAsc(ids).stream()
                        .collect(Collectors.groupingBy(PaymentPlanView::getEstateId));

        // Photos, then every photo's URL from storage, in two more queries.
        // Storage builds the public address, so it is built in one place.
        Map<UUID, List<EstatePhotoView>> photosByEstate = ids.isEmpty() ? Map.of()
                : photos.findByEstateIdInOrderByPositionAsc(ids).stream()
                        .sorted(Comparator.comparing(EstatePhotoView::isCover).reversed())
                        .collect(Collectors.groupingBy(EstatePhotoView::getEstateId, LinkedHashMap::new,
                                Collectors.toList()));
        Set<UUID> fileIds = new HashSet<>();
        photosByEstate.values().forEach(list -> list.forEach(p -> fileIds.add(p.getFileId())));
        page.forEach(l -> {
            if (l.getCoverFileId() != null) {
                fileIds.add(l.getCoverFileId());
            }
        });
        Map<UUID, String> urls = new HashMap<>();
        storage.findAll(fileIds).forEach(f -> {
            if (f.publicUrl() != null) {
                urls.put(f.id(), f.publicUrl());
            }
        });

        // Sell my plot: each estate's resales, in one more query.
        Map<UUID, List<ResaleListingView>> resalesByEstate = ids.isEmpty() ? Map.of()
                : resales.findByEstateIdIn(ids).stream()
                        .collect(Collectors.groupingBy(ResaleListingView::getEstateId));

        Map<ListingView, MarketplaceListingDto> result = new LinkedHashMap<>();
        for (ListingView l : page) {
            List<ResaleListingView> estateResales = resalesByEstate.getOrDefault(l.getEstateId(), List.of());
            List<FeeView> estateFees = feesByEstate.getOrDefault(l.getEstateId(), List.of());
            List<PriceTierView> estateTiers = tiersByEstate.getOrDefault(l.getEstateId(), List.of());
            List<PaymentPlanView> estatePlans = plansByEstate.getOrDefault(l.getEstateId(), List.of());
            List<PenaltyTierView> estatePenalties = penaltiesByEstate.getOrDefault(l.getEstateId(), List.of());
            result.put(l, new MarketplaceListingDto(
                    l.getEstateId(),
                    l.getName(),
                    l.getArea(),
                    l.getCity(),
                    l.getState(),
                    l.getDescription(),
                    amenitiesByEstate.getOrDefault(l.getEstateId(), List.of()),
                    l.getCoverFileId() == null ? null : urls.get(l.getCoverFileId()),
                    l.getTitleType() == null ? null : l.getTitleType().getValue(),
                    l.getTitleStatus() == null ? null : l.getTitleStatus().getValue(),
                    l.getTitleVerifiedAt(),
                    l.getLastVerifiedAt(),
                    estateTiers.stream()
                            .map(tier -> toDto(tier, estateFees, l.getCornerPremiumPct(), estatePlans,
                                    estatePenalties))
                            .toList(),
                    l.getCornerPremiumPct(),
                    l.getIntent() == null ? null : l.getIntent().getValue(),
                    l.getPublishedAt(),
                    new SellerDto(l.getBranchName(), l.getCompanyName(), office(l)),
                    true,
                    l.getFromPrice(),
                    l.getFromPriceCurrency(),
                    l.getPlotsRemaining(),
                    l.getFootprint() != null,
                    checksByEstate.getOrDefault(l.getEstateId(), List.of()).stream()
                            .map(MarketplaceQueryService::toDto).toList(),
                    costDisclosure(estateFees, estateTiers,
                            refundByEstate.get(l.getEstateId()),
                            defaultsByEstate.get(l.getEstateId()),
                            estatePenalties, estatePlans),
                    photosByEstate.getOrDefault(l.getEstateId(), List.of()).stream()
                            .filter(p -> urls.containsKey(p.getFileId()))
                            .map(p -> new MarketplacePhotoDto(urls.get(p.getFileId()), p.getCaption()))
                            .toList(),
                    estateResales.size(),
                    estateResales.stream().filter(r -> r.getCurrency() == l.getFromPriceCurrency())
                            .map(ResaleListingView::getPrice).filter(Objects::nonNull)
                            .min(Comparator.naturalOrder()).orElse(null)));
        }
        return result;
    }

    private static MarketplacePriceTierDto toDto(
            PriceTierView t, List<FeeView> estateFees, BigDecimal cornerPremiumPct, List<PaymentPlanView> plans,
            List<PenaltyTierView> penalties) {
        String availability = t.getPlotsRemaining() == 0 ? "sold_out"
                : t.getPlotsRemaining() <= LOW_STOCK_THRESHOLD ? "low_stock" : "available";
        return new MarketplacePriceTierDto(t.getTierId(), t.getSizeSqm(), t.getActualAreaSqm(), t.getLabel(),
                t.getPrice(), t.getCurrency(), t.getPricePerSqm(), availability, t.getPlotsRemaining(),
                commitmentFor(t, estateFees, cornerPremiumPct), plansAt(t.getPrice(), plans, penalties));
    }

    /**
     * PL-2: each declared plan at this price, worked out by HomeTrue; a plan a
     * fixed deposit makes impossible at this price is left out.
     */
    private static List<MarketplacePaymentPlanDto> plansAt(BigDecimal price, List<PaymentPlanView> plans,
                                                           List<PenaltyTierView> penalties) {
        if (price == null) {
            return List.of();
        }
        return plans.stream()
                .flatMap(plan -> plan.getKind() == PlanKind.FLEXIBLE ? Stream.of(flexibleAt(price, plan))
                        : InstalmentPlanCalculator.quote(price, plan.terms())
                        .map(q -> new MarketplacePaymentPlanDto(plan.getPlanId(), plan.getName(),
                                plan.getFrequency().getValue(), q.deposit(), q.instalmentCount(), q.instalment(),
                                q.lastInstalment(), q.planTotal(), q.premium(), lateSteps(q.instalment(), penalties),
                                PlanKind.INSTALMENT.getValue(), null))
                        .stream())
                .toList();
    }

    /** A flexible plan at this price: no schedule, so nothing to be late for. */
    private static MarketplacePaymentPlanDto flexibleAt(BigDecimal price, PaymentPlanView plan) {
        FlexiblePlanCalculator.Quote q = FlexiblePlanCalculator.quote(price, plan.flexibleTerms());
        return new MarketplacePaymentPlanDto(plan.getPlanId(), plan.getName(), null, q.minimumToStart(), null, null,
                null, q.planTotal(), q.premium(), List.of(), PlanKind.FLEXIBLE.getValue(), q);
    }

    /** The declared steps on one instalment, in naira. */
    private static List<ExitCostsDto.PenaltyStepDto> lateSteps(BigDecimal instalment,
                                                               List<PenaltyTierView> penalties) {
        return penalties.stream()
                .map(p -> new ExitCostsDto.PenaltyStepDto(p.getMonthsLate(), p.getPenaltyPct(),
                        InstalmentPlanCalculator.penaltyOn(instalment, p.getPenaltyPct())))
                .toList();
    }

    private static MarketplaceVerificationCheckDto toDto(VerificationCheckView c) {
        return new MarketplaceVerificationCheckDto(
                c.getCheckType().getValue(),
                c.getStatus().getValue(),
                c.getVerificationSource() == null ? null : c.getVerificationSource().getValue(),
                c.getLastVerifiedAt());
    }


    // ------------------------------------------------------------------
    // Full cost disclosure
    // ------------------------------------------------------------------

    /**
     * The true cost of one tier. Computed here rather than in the view
     * because money is {@link BigDecimal} arithmetic, and because the same
     * sums must come out identically for a developer reading their own
     * declaration and a buyer reading the listing.
     */
    private static TierCommitmentDto commitmentFor(
            PriceTierView tier, List<FeeView> estateFees, BigDecimal cornerPremiumPct) {

        if (tier.getPrice() == null) {
            return null;
        }
        List<CommitmentCalculator.DeclaredFee> declared = estateFees.stream()
                .map(MarketplaceQueryService::toDeclaredFee)
                .toList();

        CommitmentCalculator.TierCommitment commitment = CommitmentCalculator.forTier(
                tier.getPrice(), tier.getCurrency().name(), cornerPremiumPct, declared);

        return new TierCommitmentDto(
                commitment.landPrice(),
                tier.getCurrency(),
                money(commitment.oneOffFees()),
                money(commitment.totalCommitment()),
                commitment.totalIfCorner() == null ? null : money(commitment.totalIfCorner()),
                money(commitment.recurringFees()),
                money(commitment.optionalFees()),
                !commitment.excludedCurrency().isEmpty(),
                commitment.hasAdditionalCost());
    }

    /**
     * The breakdown, plus both exits priced together (DF-3).
     * <p>
     * Null when the estate predates the disclosure requirement — an honest
     * absence. Fabricating an empty schedule for a grandfathered estate
     * would tell a buyer "no extra charges" on the authority of nobody.
     */
    private static CostDisclosureDto costDisclosure(
            List<FeeView> estateFees,
            List<PriceTierView> estateTiers,
            RefundTermsView refund,
            DefaultTermsView defaults,
            List<PenaltyTierView> penalties,
            List<PaymentPlanView> plans) {

        if (estateFees.isEmpty() && refund == null && defaults == null) {
            return null;
        }
        return new CostDisclosureDto(
                estateFees.stream().map(MarketplaceQueryService::toDto).toList(),
                exitCosts(estateFees, estateTiers, refund, defaults, penalties, plans));
    }

    /**
     * Both ways out, on one screen and in naira.
     * <p>
     * Computed against the <strong>cheapest tier</strong>, named in the
     * response so it is never mistaken for a particular buyer's position.
     * Per-tier exit costs would be more precise and far harder to read; the
     * cheapest tier is the one a buyer weighing entry is most likely to be
     * looking at, and the basis is stated either way.
     */
    private static ExitCostsDto exitCosts(
            List<FeeView> estateFees,
            List<PriceTierView> estateTiers,
            RefundTermsView refund,
            DefaultTermsView defaults,
            List<PenaltyTierView> penalties,
            List<PaymentPlanView> plans) {

        PriceTierView basis = estateTiers.stream()
                .filter(tier -> tier.getPrice() != null)
                .min(Comparator.comparing(PriceTierView::getPrice))
                .orElse(null);
        if (basis == null || (refund == null && defaults == null)) {
            return null;
        }

        ExitCostsDto.RefundOutcomeDto withdrawal = null;
        if (refund != null) {
            // Fees the policy says never come back, in the tier's own
            // currency. Anything in another currency is left out rather than
            // converted at a rate nobody agreed to.
            Set<String> neverReturned = refund.getNonRefundableFeeTypes() == null ? Set.of()
                    : Set.of(refund.getNonRefundableFeeTypes());
            BigDecimal nonRefundable = estateFees.stream()
                    .filter(fee -> neverReturned.contains(FeeType.fromValue(fee.getFeeType()).name()))
                    .filter(fee -> basis.getCurrency().name().equalsIgnoreCase(fee.getCurrency()))
                    .map(fee -> fee.high() == null ? BigDecimal.ZERO : fee.high())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            CommitmentCalculator.RefundOutcome outcome = CommitmentCalculator.refundOn(
                    basis.getPrice(), basis.getCurrency().name(), refund.getDeductionPct(),
                    refund.getProcessingDays(), nonRefundable);
            withdrawal = new ExitCostsDto.RefundOutcomeDto(
                    outcome.basis(), outcome.deductionPct(), outcome.deduction(),
                    outcome.nonRefundableFees(), outcome.refundAmount(), outcome.totalLoss(),
                    outcome.processingDays());
        }

        // Decided with the user: a penalty is a share of the overdue instalment,
        // so it's priced on one instalment of the estate's first instalment plan at this
        // tier. No plans: outright buyers have nothing to fall behind on.
        Optional<MarketplacePaymentPlanDto> penaltyBasis = plansAt(basis.getPrice(), plans, penalties).stream()
                .filter(p -> PlanKind.INSTALMENT.getValue().equals(p.kind()))
                .findFirst();
        List<ExitCostsDto.PenaltyStepDto> steps = penaltyBasis
                .map(MarketplacePaymentPlanDto::ifAnInstalmentIsLate).orElse(List.of());

        ExitCostsDto.RevocationDto revocation = defaults == null ? null
                : new ExitCostsDto.RevocationDto(
                        defaults.getRevocationAfterMonths(),
                        RevocationTerms.offered(defaults.getRevocationAfterMonths(),
                                defaults.getRevocationNoticeDays()).noticeDays(),
                        true,
                        defaults.getDevelopmentDeadlineMonths(),
                        defaults.isTransferRequiresConsent());

        // Arithmetic, not an opinion: true when neither route out is free.
        boolean bothCost = withdrawal != null && withdrawal.totalLoss().signum() > 0
                && steps.stream().anyMatch(step -> step.amount().signum() > 0);

        return new ExitCostsDto(
                basis.getTierId(), basis.getLabel(), basis.getPrice(), basis.getCurrency().name(),
                withdrawal, steps, penaltyBasis.map(MarketplacePaymentPlanDto::name).orElse(null),
                penaltyBasis.map(MarketplacePaymentPlanDto::instalment).orElse(null),
                penalties.isEmpty() || defaults == null ? null : defaults.isPenaltiesRefundable(), revocation, bothCost);
    }

    private static CommitmentCalculator.DeclaredFee toDeclaredFee(FeeView fee) {
        return new CommitmentCalculator.DeclaredFee(
                FeeType.fromValue(fee.getFeeType()),
                fee.getLabel(),
                fee.getCurrency(),
                fee.low(),
                fee.high(),
                fee.isFixed(),
                fee.isMandatory(),
                DueTrigger.fromValue(fee.getDueTrigger()));
    }

    private static PublicFeeDto toDto(FeeView fee) {
        return new PublicFeeDto(
                // The views return the column verbatim, and the column holds
                // the Java constant (@Enumerated(STRING)). Translate at the
                // DTO boundary or the public payload publishes APPLICATION
                // where every other enum in this API says "application".
                FeeType.fromValue(fee.getFeeType()).getValue(),
                fee.getLabel(),
                PublicFeeDto.range(fee.low(), fee.high()),
                fee.getCurrency(),
                fee.isFixed(),
                fee.getVariationBasis(),
                DueTrigger.fromValue(fee.getDueTrigger()).getValue(),
                fee.isRefundable(),
                fee.isMandatory(),
                fee.getNotes());
    }

    private static MoneyRangeDto money(CommitmentCalculator.Money amount) {
        return new MoneyRangeDto(amount.min(), amount.max(), amount.isRange());
    }

    /**
     * Not published, not eligible, or not an estate at all: deliberately one
     * answer. Telling a stranger "this exists but is hidden" would reveal a
     * suspended company's inventory or a contested estate's existence.
     */
    public static class ListingNotFound extends NoSuchElementException {
    }

    /** Null rather than an object of nulls: absent is absent. */
    private static BranchOfficeDto office(ListingView l) {
        if (l.getBranchStreet() == null && l.getBranchCity() == null && l.getBranchState() == null
                && l.getBranchPhone() == null && l.getBranchEmail() == null) {
            return null;
        }
        return new BranchOfficeDto(l.getBranchStreet(), l.getBranchCity(), l.getBranchState(),
                l.getBranchPhone(), l.getBranchEmail());
    }
}
