package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonWriter;
import com.techcomfort.landvaultbackend.common.PlotPricing;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateSummaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotCountsDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateAmenity;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateVerificationCheck;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateAmenityRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateSpecifications;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateTitleRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateVerificationCheckRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotSpecifications;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PriceTierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading a tenant's own inventory. Read-only by construction — no method
 * here writes anything, and <strong>none records an audit entry</strong>: a
 * log that grows by one row every time somebody scrolls a list stops being
 * readable as a record of what was done.
 * <p>
 * <strong>Tenant and branch isolation is not implemented here.</strong> It
 * is enforced by the row-level-security policies added in changeset 044,
 * which the database applies to every statement these repositories issue.
 * The one scope check this service does make is
 * {@link #requireTenant(TenantScope)} — rejecting a caller with no tenant at
 * all, which is a 403-shaped mistake (platform staff on a portal surface),
 * not an isolation boundary.
 */
@Service
@RequiredArgsConstructor
public class PortalEstateQueryService {

    private final EstateRepository estateRepository;
    private final EstateAmenityRepository estateAmenityRepository;
    private final BlockRepository blockRepository;
    private final PriceTierRepository priceTierRepository;
    private final PlotRepository plotRepository;
    private final EstateTitleRepository estateTitleRepository;
    private final EstateVerificationCheckRepository verificationCheckRepository;

    // --- estate list ---

    /**
     * The directory. Three queries total regardless of page size: the page
     * itself, one grouped plot-count query, and one batched area query —
     * never a per-row lookup for either.
     */
    @Transactional(readOnly = true)
    public PageResponse<EstateSummaryDto> listEstates(
            String state, Boolean published, String intent, UUID branchId, String query, Pageable pageable) {

        requireTenant(currentScope());

        Page<Estate> page = estateRepository.findAll(
                EstateSpecifications.matching(state, published, intent, branchId, query), pageable);

        List<UUID> ids = page.getContent().stream().map(Estate::getId).toList();
        Map<UUID, BigDecimal> areas = footprintAreas(ids);
        Map<UUID, PlotCountsDto> counts = plotCounts(ids);

        return PageResponses.from(page, estate -> new EstateSummaryDto(
                estate.getId(),
                estate.getBranchId(),
                estate.getName(),
                estate.getSlug(),
                estate.getArea(),
                estate.getCity(),
                estate.getState(),
                estate.getIntent() == null ? null : estate.getIntent().getValue(),
                Boolean.TRUE.equals(estate.getPublished()),
                estate.getPublishedAt(),
                estate.getFootprint() != null,
                areas.get(estate.getId()),
                counts.getOrDefault(estate.getId(), PlotCountsDto.empty()),
                estate.getCreatedAt()));
    }

    // --- estate detail ---

    @Transactional(readOnly = true)
    public EstateDetailDto getEstate(UUID estateId) {
        Estate estate = requireVisibleEstate(estateId);

        List<String> amenities = estateAmenityRepository.findByEstateIdOrderByNameAsc(estateId).stream()
                .map(EstateAmenity::getName)
                .toList();
        List<BlockDto> blocks = blockRepository.findByEstateIdOrderByNameAsc(estateId).stream()
                .map(PortalEstateQueryService::toDto)
                .toList();
        List<PriceTierDto> tiers = priceTierRepository.findByEstateIdOrderBySizeSqmAsc(estateId).stream()
                .map(PortalEstateQueryService::toDto)
                .toList();
        EstateTitleDto title = estateTitleRepository.findByEstateId(estateId)
                .map(PortalEstateQueryService::toDto)
                .orElse(null);
        List<VerificationCheckDto> checks = verificationCheckRepository.findByEstateId(estateId).stream()
                .map(PortalEstateQueryService::toDto)
                .toList();

        return new EstateDetailDto(
                estate.getId(),
                estate.getTenantId(),
                estate.getBranchId(),
                estate.getName(),
                estate.getSlug(),
                estate.getDescription(),
                estate.getArea(),
                estate.getCity(),
                estate.getState(),
                estate.getAddress(),
                estate.getCornerPremiumPct(),
                estate.getIntent() == null ? null : estate.getIntent().getValue(),
                amenities,
                Boolean.TRUE.equals(estate.getPublished()),
                estate.getPublishedAt(),
                estate.getFootprint() != null,
                footprintAreas(List.of(estateId)).get(estateId),
                blocks,
                tiers,
                title,
                checks,
                plotCounts(List.of(estateId)).getOrDefault(estateId, PlotCountsDto.empty()),
                estate.getCreatedAt());
    }

    // --- plots ---

    /**
     * One estate's plots. The tier and block lookups are loaded once for the
     * whole estate and shared across the page — a plot's price comes from
     * its tier, and fetching that tier per plot would be an N+1 on the
     * hottest read in the module.
     */
    @Transactional(readOnly = true)
    public PageResponse<PlotDetailDto> listPlots(
            UUID estateId, String status, UUID blockId, UUID priceTierId, Boolean isCorner,
            String propertyType, String listingIntent, Pageable pageable) {

        Estate estate = requireVisibleEstate(estateId);

        Page<Plot> page = plotRepository.findAll(
                PlotSpecifications.matching(
                        estateId, status, blockId, priceTierId, isCorner, propertyType, listingIntent),
                pageable);

        Map<UUID, PriceTier> tiers = tiersByIdFor(estateId);
        Map<UUID, Block> blocks = blocksByIdFor(estateId);

        return PageResponses.from(page, plot -> toDto(plot, estate, tiers, blocks));
    }

    @Transactional(readOnly = true)
    public PlotDetailDto getPlot(UUID estateId, UUID plotId) {
        Estate estate = requireVisibleEstate(estateId);
        Plot plot = plotRepository.findByIdAndEstateId(plotId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Plot " + plotId + " does not belong to this estate."));
        return toDto(plot, estate, tiersByIdFor(estateId), blocksByIdFor(estateId));
    }

    // --- geojson ---

    /**
     * The estate boundary and every plot boundary as one GeoJSON
     * {@code FeatureCollection}.
     * <p>
     * The estate feature comes first so a client drawing them in order
     * renders plots on top of the boundary rather than under it. Anything
     * without a footprint is omitted entirely — see
     * {@link GeoJsonFeatureCollectionDto}.
     */
    @Transactional(readOnly = true)
    public GeoJsonFeatureCollectionDto getGeoJson(UUID estateId) {
        Estate estate = requireVisibleEstate(estateId);

        List<GeoJsonFeatureDto> features = new ArrayList<>();
        if (estate.getFootprint() != null) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", "estate");
            properties.put("id", estate.getId());
            properties.put("name", estate.getName());
            properties.put("areaSqm", footprintAreas(List.of(estateId)).get(estateId));
            features.add(GeoJsonFeatureDto.of(
                    GeoJsonPolygonWriter.toGeoJson(estate.getFootprint()), properties));
        }

        Map<UUID, PriceTier> tiers = tiersByIdFor(estateId);
        Map<UUID, Block> blocks = blocksByIdFor(estateId);
        for (Plot plot : plotRepository.findByEstateIdAndFootprintIsNotNull(estateId)) {
            PriceTier tier = tiers.get(plot.getPriceTierId());
            BigDecimal price = tier == null ? null : PlotPricing.price(
                    tier.getPrice(), Boolean.TRUE.equals(plot.getIsCorner()), estate.getCornerPremiumPct());

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", "plot");
            properties.put("id", plot.getId());
            properties.put("plotNumber", plot.getPlotNumber());
            properties.put("status", plot.getStatus().getValue());
            properties.put("isCorner", Boolean.TRUE.equals(plot.getIsCorner()));
            properties.put("blockId", plot.getBlockId());
            properties.put("blockName", nameOf(blocks.get(plot.getBlockId())));
            properties.put("propertyType", plot.getPropertyType().getValue());
            properties.put("listingIntent", plot.getListingIntent().getValue());
            properties.put("nominalSizeSqm", plot.getNominalSizeSqm());
            properties.put("actualAreaSqm", plot.getActualAreaSqm());
            properties.put("price", price);
            properties.put("currency", tier == null ? null : tier.getCurrency());
            features.add(GeoJsonFeatureDto.of(
                    GeoJsonPolygonWriter.toGeoJson(plot.getFootprint()), properties));
        }

        return GeoJsonFeatureCollectionDto.of(features);
    }

    // --- shared ---

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    /**
     * Platform staff have no tenant scope, so on a portal surface they have
     * nothing to read. Not an isolation check — RLS does that — just a clear
     * answer instead of a silently empty page, which is what the fail-closed
     * policies would otherwise produce for them.
     */
    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.InvalidRequest(
                    "This endpoint belongs to a tenant's own portal; the caller has no tenant scope.");
        }
        return scope.tenantId();
    }

    /**
     * An estate the caller can actually see.
     * <p>
     * The {@code tenantId} passed to {@code findByIdAndTenantId} is
     * belt-and-braces over the RLS policy that has already filtered the row
     * — kept because it is the same call the write path makes, and because
     * a "not found" here must be indistinguishable from "exists but isn't
     * yours". A branch-scoped caller asking for a sibling branch's estate
     * gets the same 404 as for an id that never existed, since the policy
     * removes the row before this query ever sees it.
     */
    private Estate requireVisibleEstate(UUID estateId) {
        return estateRepository.findByIdAndTenantId(estateId, requireTenant(currentScope()))
                .orElseThrow(InventoryException.EstateNotFound::new);
    }

    private Map<UUID, BigDecimal> footprintAreas(Collection<UUID> estateIds) {
        if (estateIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> areas = new HashMap<>();
        for (Object[] row : estateRepository.footprintAreasSqm(estateIds)) {
            if (row[1] != null) {
                areas.put((UUID) row[0], new BigDecimal(row[1].toString())
                        .setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
        return areas;
    }

    private Map<UUID, PlotCountsDto> plotCounts(Collection<UUID> estateIds) {
        if (estateIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, Long>> byEstate = new HashMap<>();
        Map<UUID, Long> totals = new HashMap<>();
        for (Object[] row : plotRepository.countGroupedByEstateIdAndStatus(estateIds)) {
            UUID estateId = (UUID) row[0];
            String status = ((PlotStatus) row[1]).getValue();
            long count = ((Number) row[2]).longValue();
            byEstate.computeIfAbsent(estateId, key -> new LinkedHashMap<>()).put(status, count);
            totals.merge(estateId, count, Long::sum);
        }
        return byEstate.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new PlotCountsDto(totals.get(entry.getKey()), entry.getValue())));
    }

    private Map<UUID, PriceTier> tiersByIdFor(UUID estateId) {
        return priceTierRepository.findByEstateIdOrderBySizeSqmAsc(estateId).stream()
                .collect(Collectors.toMap(PriceTier::getId, Function.identity()));
    }

    private Map<UUID, Block> blocksByIdFor(UUID estateId) {
        return blockRepository.findByEstateIdOrderByNameAsc(estateId).stream()
                .collect(Collectors.toMap(Block::getId, Function.identity()));
    }

    private static String nameOf(Block block) {
        return block == null ? null : Optional.ofNullable(block.getLabel()).orElse(block.getName());
    }

    private static PlotDetailDto toDto(
            Plot plot, Estate estate, Map<UUID, PriceTier> tiers, Map<UUID, Block> blocks) {

        PriceTier tier = tiers.get(plot.getPriceTierId());
        boolean isCorner = Boolean.TRUE.equals(plot.getIsCorner());
        // Only a corner plot's price is affected by the premium, so echoing
        // it on a non-corner plot would suggest a discount/surcharge that
        // was never applied.
        BigDecimal premium = isCorner ? estate.getCornerPremiumPct() : null;
        BigDecimal basePrice = tier == null ? null : tier.getPrice();
        BigDecimal price = PlotPricing.price(basePrice, isCorner, estate.getCornerPremiumPct());

        return new PlotDetailDto(
                plot.getId(),
                plot.getEstateId(),
                plot.getBlockId(),
                nameOf(blocks.get(plot.getBlockId())),
                plot.getPriceTierId(),
                tier == null ? null : tier.getLabel(),
                plot.getPlotNumber(),
                isCorner,
                plot.getStatus().getValue(),
                plot.getIntent() == null ? null : plot.getIntent().getValue(),
                plot.getPropertyType().getValue(),
                plot.getListingIntent().getValue(),
                plot.getOrientation() == null ? null : plot.getOrientation().getValue(),
                plot.getNominalSizeSqm(),
                plot.getActualAreaSqm(),
                plot.getFootprint() != null,
                basePrice,
                premium,
                price,
                tier == null ? null : tier.getCurrency(),
                PlotPricing.pricePerSqm(price, plot.getNominalSizeSqm()));
    }

    private static BlockDto toDto(Block block) {
        return new BlockDto(block.getId(), block.getEstateId(), block.getName(), block.getLabel());
    }

    private static PriceTierDto toDto(PriceTier tier) {
        return new PriceTierDto(tier.getId(), tier.getEstateId(), tier.getTierType().getValue(),
                tier.getSizeSqm(), tier.getPrice(), tier.getCurrency(), tier.getLabel());
    }

    private static EstateTitleDto toDto(EstateTitle title) {
        return new EstateTitleDto(title.getId(), title.getEstateId(), title.getTitleType().getValue(),
                title.getTitleNumber(), title.getIssuedDate(),
                title.getSurveyPlanDocumentId(), title.getDeedDocumentId());
    }

    private static VerificationCheckDto toDto(EstateVerificationCheck check) {
        return new VerificationCheckDto(
                check.getId(), check.getEstateId(), check.getCheckType().getValue(),
                check.getStatus().getValue(),
                check.getVerificationSource() == null ? null : check.getVerificationSource().getValue(),
                check.getLastVerifiedAt(), check.getNotes());
    }
}
