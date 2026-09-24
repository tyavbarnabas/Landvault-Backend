package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.marketplace.dto.CostDisclosureDto;
import com.techcomfort.landvaultbackend.marketplace.dto.ExitCostsDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MoneyRangeDto;
import com.techcomfort.landvaultbackend.marketplace.dto.PublicFeeDto;
import com.techcomfort.landvaultbackend.marketplace.dto.TierCommitmentDto;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.DefaultTermsView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.FeeView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PenaltyTierView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.RefundTermsView;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.DefaultTermsViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.FeeViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PenaltyTierViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.RefundTermsViewRepository;
import com.techcomfort.landvaultbackend.common.CommitmentCalculator;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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
    private final FeeViewRepository fees;
    private final RefundTermsViewRepository refundTerms;
    private final DefaultTermsViewRepository defaultTerms;
    private final PenaltyTierViewRepository penaltyTiers;

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

        Map<ListingView, MarketplaceListingDto> result = new LinkedHashMap<>();
        for (ListingView l : page) {
            List<FeeView> estateFees = feesByEstate.getOrDefault(l.getEstateId(), List.of());
            List<PriceTierView> estateTiers = tiersByEstate.getOrDefault(l.getEstateId(), List.of());
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
                    estateTiers.stream()
                            .map(tier -> toDto(tier, estateFees, l.getCornerPremiumPct()))
                            .toList(),
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
                            .map(MarketplaceQueryService::toDto).toList(),
                    costDisclosure(estateFees, estateTiers,
                            refundByEstate.get(l.getEstateId()),
                            defaultsByEstate.get(l.getEstateId()),
                            penaltiesByEstate.getOrDefault(l.getEstateId(), List.of()))));
        }
        return result;
    }

    private static MarketplacePriceTierDto toDto(
            PriceTierView t, List<FeeView> estateFees, BigDecimal cornerPremiumPct) {
        String availability = t.getPlotsRemaining() == 0 ? "sold_out"
                : t.getPlotsRemaining() <= LOW_STOCK_THRESHOLD ? "low_stock" : "available";
        return new MarketplacePriceTierDto(t.getTierId(), t.getSizeSqm(), t.getActualAreaSqm(), t.getLabel(),
                t.getPrice(), t.getCurrency(), t.getPricePerSqm(), availability, t.getPlotsRemaining(),
                commitmentFor(t, estateFees, cornerPremiumPct));
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
            List<PenaltyTierView> penalties) {

        if (estateFees.isEmpty() && refund == null && defaults == null) {
            return null;
        }
        return new CostDisclosureDto(
                estateFees.stream().map(MarketplaceQueryService::toDto).toList(),
                exitCosts(estateFees, estateTiers, refund, defaults, penalties));
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
            List<PenaltyTierView> penalties) {

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

        List<ExitCostsDto.PenaltyStepDto> steps = penalties.stream()
                .map(tier -> CommitmentCalculator.penaltyStep(
                        tier.getMonthsLate(), tier.getPenaltyPct(), basis.getPrice()))
                .map(step -> new ExitCostsDto.PenaltyStepDto(
                        step.monthsLate(), step.penaltyPct(), step.amount()))
                .toList();

        ExitCostsDto.RevocationDto revocation = defaults == null ? null
                : new ExitCostsDto.RevocationDto(
                        defaults.getRevocationTrigger(),
                        defaults.getRevocationNoticeDays(),
                        defaults.getOnRevocationRefund(),
                        defaults.getDevelopmentDeadlineMonths(),
                        defaults.isTransferRequiresConsent());

        // Arithmetic, not an opinion: true when neither route out is free.
        boolean bothCost = withdrawal != null && withdrawal.totalLoss().signum() > 0
                && steps.stream().anyMatch(step -> step.amount().signum() > 0);

        return new ExitCostsDto(
                basis.getTierId(), basis.getLabel(), basis.getPrice(), basis.getCurrency().name(),
                withdrawal, steps, revocation, bothCost);
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
}
