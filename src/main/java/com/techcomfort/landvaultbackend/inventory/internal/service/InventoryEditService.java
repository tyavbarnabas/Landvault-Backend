package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import java.util.Set;
import java.util.Locale;
import java.util.HashMap;
import java.time.Instant;
import com.techcomfort.landvaultbackend.inventory.dto.PlotStatusChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.ChangePlotStatusRequest;
import com.techcomfort.landvaultbackend.inventory.dto.BulkPlotStatusRequest;
import com.techcomfort.landvaultbackend.inventory.PlotHistoryProbe;
import org.locationtech.jts.geom.Polygon;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.dto.PlotTierChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotBoundaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.MovePlotTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CorrectPlotBoundaryRequest;
import com.techcomfort.landvaultbackend.conflicts.ConflictDetectionApi;
import com.techcomfort.landvaultbackend.common.PlotPricing;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotCountsDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierImpactDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierUpdateDto;
import com.techcomfort.landvaultbackend.inventory.dto.UpdateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.UpdatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TierType;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PriceTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Editing inventory that already exists — slice 1: tier price, label and
 * size, and block names; slice 2: a plot's boundary (IE-9) and its tier
 * (IE-10); slice 3: withholding plots singly or in bulk (IE-7/IE-8),
 * retiring tiers (IE-5) and withdrawing untouched plots (IE-11). Creation lives in {@code PortalEstateService}.
 * <p>
 * Every edit is audited with the previous value in the free-text
 * {@code detail}: a price change is exactly what a tenant later disputes. An
 * edit that changes nothing writes nothing, the same no-op rule publish uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryEditService {

    private static final List<PlotStatus> AVAILABLE =
            List.of(PlotStatus.AVAILABLE_DEV, PlotStatus.AVAILABLE_INV);

    private final EstateRepository estateRepository;
    private final PriceTierRepository priceTierRepository;
    private final PlotRepository plotRepository;
    private final BlockRepository blockRepository;
    private final AuditApi auditApi;
    private final GeoJsonPolygonParser geoJsonParser;
    private final GeometryCalculator geometry;
    private final ConflictDetectionApi conflictDetection;
    private final PlotStatusWriter statusWriter;
    private final PlotHistoryProbe plotHistory;

    // --- price tier ---

    @Transactional
    public PriceTierUpdateDto updatePriceTier(UUID estateId, UUID tierId, UpdatePriceTierRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        PriceTier tier = requireTier(estateId, tierId);

        // A different type or currency is a different tier. Echoing the
        // current value back is fine; anything else is refused, not ignored.
        if (request.tierType() != null && TierType.fromValue(request.tierType()) != tier.getTierType()) {
            throw new InventoryException.ImmutableField("TIER_TYPE_IMMUTABLE",
                    "A tier's type cannot change. Create a new tier and move the plots to it.");
        }
        if (request.currency() != null && request.currency() != tier.getCurrency()) {
            throw new InventoryException.ImmutableField("TIER_CURRENCY_IMMUTABLE",
                    "A tier's currency cannot change. Create a new tier in that currency and move the plots to it.");
        }
        if (request.sizeSqm() != null && tier.getTierType() != TierType.LAND_SIZE) {
            throw new InventoryException.InvalidRequest(
                    "A UNIT_TYPE tier has no size of its own — its label carries the meaning.");
        }

        List<String> changes = new ArrayList<>();

        if (request.price() != null && request.price().compareTo(tier.getPrice()) != 0) {
            changes.add("price " + tier.getCurrency() + " " + tier.getPrice().toPlainString()
                    + " -> " + tier.getCurrency() + " " + request.price().toPlainString());
            tier.setPrice(request.price());
        }

        if (request.label() != null) {
            String label = request.label().isBlank() ? null : request.label().trim();
            if (!Objects.equals(label, tier.getLabel())) {
                changes.add("label '" + nullToEmpty(tier.getLabel()) + "' -> '" + nullToEmpty(label) + "'");
                tier.setLabel(label);
            }
        }

        BigDecimal previousSize = tier.getSizeSqm();
        boolean sizeChanged = request.sizeSqm() != null && request.sizeSqm().compareTo(previousSize) != 0;
        if (sizeChanged) {
            if (priceTierRepository.existsByEstateIdAndSizeSqmAndIdNot(estateId, request.sizeSqm(), tierId)) {
                throw new InventoryException.DuplicateRecord(
                        "A tier for " + request.sizeSqm().toPlainString() + " sqm already exists on this estate.");
            }
            tier.setSizeSqm(request.sizeSqm());
        }

        if (changes.isEmpty() && !sizeChanged) {
            return new PriceTierUpdateDto(toDto(tier), null);
        }

        // Through the repository, not EntityManager.flush(): a constraint
        // violation here must arrive as DataIntegrityViolationException so
        // the handler maps it to 409 (see AGENTS.md). Also takes the tier
        // row's lock before the plots are touched — the plot-release
        // function waits on that lock, so a hold ending mid-edit reads the
        // new size rather than the old.
        tier = priceTierRepository.saveAndFlush(tier);

        PriceTierUpdateDto.SizeChange sizeChange = null;
        if (sizeChanged) {
            int updated = plotRepository.resizeAvailablePlotsOnTier(
                    tierId, tier.getSizeSqm(), scope.userId().toString());
            PlotCountsDto kept = countsForTier(tierId, true);
            changes.add("size " + previousSize.toPlainString() + " -> " + tier.getSizeSqm().toPlainString()
                    + " sqm, applied to " + updated + " available plot(s); " + kept.total()
                    + " reserved or sold plot(s) kept " + previousSize.toPlainString() + " sqm");
            sizeChange = new PriceTierUpdateDto.SizeChange(
                    previousSize, tier.getSizeSqm(), updated, kept,
                    kept.total() == 0
                            ? "Every plot on this tier now carries the new size."
                            : "Reserved and sold plots keep the size their buyer agreed to, and withheld plots "
                                    + "keep theirs while off the market. A reserved or withheld plot that returns "
                                    + "to the market takes the tier's size at that point.");
        }

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.price_tier_updated", "price_tier", tierId, estate.getTenantId(),
                "Price tier on estate " + estateId + " changed: " + String.join("; ", changes) + "."));
        log.info("Price tier {} on estate {} updated by {}", tierId, estateId, scope.userId());

        return new PriceTierUpdateDto(toDto(tier), sizeChange);
    }

    /** IE-2. A read, so any holder of {@code portal.estates.view} may preview. */
    @Transactional(readOnly = true)
    public PriceTierImpactDto priceTierImpact(UUID estateId, UUID tierId) {
        TenantScope scope = currentScope();
        requireVisibleEstate(estateId, requireTenant(scope));
        requireTier(estateId, tierId);
        return new PriceTierImpactDto(tierId, countsForTier(tierId, false));
    }

    // --- block ---

    @Transactional
    public BlockDto updateBlock(UUID estateId, UUID blockId, UpdateBlockRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        Block block = blockRepository.findByIdAndEstateId(blockId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Block " + blockId + " does not belong to this estate."));

        List<String> changes = new ArrayList<>();

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new InventoryException.InvalidRequest("A block name cannot be blank.");
            }
            String name = request.name().trim();
            if (!name.equals(block.getName())) {
                if (blockRepository.existsByEstateIdAndNameIgnoreCaseAndIdNot(estateId, name, blockId)) {
                    throw new InventoryException.DuplicateRecord(
                            "Block '" + name + "' already exists on this estate.");
                }
                changes.add("name '" + block.getName() + "' -> '" + name + "'");
                block.setName(name);
            }
        }

        if (request.label() != null) {
            String label = request.label().isBlank() ? null : request.label().trim();
            if (!Objects.equals(label, block.getLabel())) {
                changes.add("label '" + nullToEmpty(block.getLabel()) + "' -> '" + nullToEmpty(label) + "'");
                block.setLabel(label);
            }
        }

        if (changes.isEmpty()) {
            return toDto(block);
        }

        // saveAndFlush so a lost race on uq_blocks_estate_name surfaces here
        // as a translated DataIntegrityViolationException — a 409 — not as a
        // raw error at commit.
        block = blockRepository.saveAndFlush(block);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.block_updated", "block", blockId, estate.getTenantId(),
                "Block on estate " + estateId + " changed: " + String.join("; ", changes) + "."));
        return toDto(block);
    }

    // --- plot boundary (IE-9) ---

    /**
     * Replaces a plot's boundary. Available plots only — a reserved or sold
     * plot's boundary is what its buyer agreed to, and correcting it is a
     * matter for a person and a legal process, not a portal edit.
     * <p>
     * The plot row is locked first, the same lock the reservation function
     * takes, so a buyer reserving this plot at the same instant either wins
     * first (and this is refused) or waits until the correction commits.
     * <p>
     * Recomputes {@code actualAreaSqm} — a stale surveyed area is worse than
     * a missing one, because it looks authoritative — and re-runs plot overlap
     * detection for the estate, which both clears pairs that no longer overlap
     * and records any new one. Plot overlaps are same-company by construction,
     * so they warn rather than block.
     */
    @Transactional
    public PlotBoundaryDto correctPlotBoundary(UUID estateId, UUID plotId, CorrectPlotBoundaryRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        Plot plot = requireEditablePlot(estateId, plotId);

        Polygon footprint = geoJsonParser.parse(request.footprint(), "footprint");
        if (footprint == null) {
            throw new InventoryException.InvalidGeometry("footprint", "A boundary is required.");
        }
        if (estate.getFootprint() != null && !geometry.isWithin(footprint, estate.getFootprint())) {
            throw new InventoryException.PlotOutsideEstate(
                    "The corrected boundary for " + plotLabel(plot) + " falls outside the estate's boundary.");
        }

        BigDecimal previousArea = plot.getActualAreaSqm();
        int before = conflictDetection.detectForEstatePlots(estateId);

        plot.setFootprint(footprint);
        plot.setActualAreaSqm(geometry.areaInSquareMetres(footprint));
        plotRepository.saveAndFlush(plot);

        int after = conflictDetection.detectForEstatePlots(estateId);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.plot_boundary_corrected", "plot", plotId, estate.getTenantId(),
                plotLabel(plot) + " on estate " + estateId + ": boundary corrected; surveyed area "
                        + (previousArea == null ? "none" : previousArea.toPlainString()) + " -> "
                        + plot.getActualAreaSqm().toPlainString() + " sqm; overlapping plot pairs in estate "
                        + before + " -> " + after + "."));
        log.info("Plot {} boundary corrected on estate {} by {}", plotId, estateId, scope.userId());

        return new PlotBoundaryDto(plotId, previousArea, plot.getActualAreaSqm(), before, after);
    }

    // --- plot tier (IE-10) ---

    /**
     * Moves a plot to another tier on the same estate. Changes its price and,
     * for a land tier, its nominal size — the number on the deed — so it is
     * available plots only, under the same row lock as a boundary correction.
     * <p>
     * Size rule: a {@code LAND_SIZE} tier's size always applies (an override
     * is refused, not ignored). A {@code UNIT_TYPE} tier has no size of its
     * own, so the plot keeps its current size unless an override is given —
     * never silently cleared, never left sizeless by the move.
     * <p>
     * Same currency only: a different one would quietly turn a naira plot
     * into a dollar plot.
     */
    @Transactional
    public PlotTierChangeDto movePlotToTier(UUID estateId, UUID plotId, MovePlotTierRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        Plot plot = requireEditablePlot(estateId, plotId);
        PriceTier current = requireTier(estateId, plot.getPriceTierId());
        PriceTier target = requireTier(estateId, request.tierId());

        if (target.getRetiredAt() != null && !target.getId().equals(current.getId())) {
            throw new InventoryException.ImmutableField("TIER_RETIRED",
                    "'" + nullToEmpty(target.getLabel()) + "' is retired and accepts no new plots.");
        }
        if (target.getCurrency() != current.getCurrency()) {
            throw new InventoryException.ImmutableField("TIER_CURRENCY_MISMATCH",
                    "That tier is priced in " + target.getCurrency() + " and this plot in "
                            + current.getCurrency() + ". A plot can only move between tiers in the same currency.");
        }
        // A land plot moves between land tiers, a built plot between unit
        // tiers. Crossing would leave a plot marked as bare land but priced
        // as a 3-bedroom terrace (or the reverse) — found in the IE-10
        // walkthrough. Changing what a plot physically is isn't a tier move.
        if (PortalEstateService.expectedPropertyType(target) != plot.getPropertyType()) {
            throw new InventoryException.ImmutableField("PROPERTY_TYPE_MISMATCH",
                    plotLabel(plot) + " is " + plot.getPropertyType().getValue() + " and '"
                            + nullToEmpty(target.getLabel()) + "' is a " + target.getTierType().getValue()
                            + " tier. A plot can only move to a tier of the same kind.");
        }
        if (target.getTierType() == TierType.LAND_SIZE && request.nominalSizeSqmOverride() != null) {
            throw new InventoryException.InvalidRequest(
                    "A LAND_SIZE tier sets the plot's size; nominalSizeSqmOverride is only for UNIT_TYPE tiers.");
        }

        BigDecimal previousSize = plot.getNominalSizeSqm();
        BigDecimal newSize = target.getTierType() == TierType.LAND_SIZE
                ? target.getSizeSqm()
                : (request.nominalSizeSqmOverride() != null ? request.nominalSizeSqmOverride() : previousSize);

        BigDecimal previousPrice = priceOf(plot, current, estate);
        if (target.getId().equals(current.getId()) && sameAmount(previousSize, newSize)) {
            return new PlotTierChangeDto(plotId, current.getId(), current.getId(),
                    previousSize, previousSize, previousPrice, previousPrice, current.getCurrency());
        }

        plot.setPriceTierId(target.getId());
        plot.setNominalSizeSqm(newSize);
        plotRepository.saveAndFlush(plot);
        BigDecimal newPrice = priceOf(plot, target, estate);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.plot_tier_changed", "plot", plotId, estate.getTenantId(),
                plotLabel(plot) + " on estate " + estateId + ": tier '" + nullToEmpty(current.getLabel()) + "' -> '"
                        + nullToEmpty(target.getLabel()) + "'; price " + current.getCurrency() + " "
                        + previousPrice.toPlainString() + " -> " + newPrice.toPlainString() + "; size "
                        + (previousSize == null ? "none" : previousSize.toPlainString()) + " -> "
                        + (newSize == null ? "none" : newSize.toPlainString()) + " sqm."));
        log.info("Plot {} moved to tier {} on estate {} by {}", plotId, target.getId(), estateId, scope.userId());

        return new PlotTierChangeDto(plotId, current.getId(), target.getId(),
                previousSize, newSize, previousPrice, newPrice, target.getCurrency());
    }

    /** Locked, owned, and available — or refused. Locking before the status check is what makes the check hold. */
    private Plot requireEditablePlot(UUID estateId, UUID plotId) {
        Plot plot = plotRepository.findForUpdate(plotId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Plot " + plotId + " does not belong to this estate."));
        if (!AVAILABLE.contains(plot.getStatus())) {
            throw new InventoryException.PlotNotEditable(plotLabel(plot), plot.getStatus().getValue());
        }
        return plot;
    }

    private static BigDecimal priceOf(Plot plot, PriceTier tier, Estate estate) {
        return PlotPricing.price(tier.getPrice(), Boolean.TRUE.equals(plot.getIsCorner()), estate.getCornerPremiumPct());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private String plotLabel(Plot plot) {
        if (plot.getBlockId() == null) {
            return "Plot " + plot.getPlotNumber();
        }
        return blockRepository.findById(plot.getBlockId())
                .map(b -> "Block " + b.getName() + ", Plot " + plot.getPlotNumber())
                .orElse("Plot " + plot.getPlotNumber());
    }

    // --- plot status (IE-7 single, IE-8 bulk) ---

    /**
     * One plot. Reported the same way as a bulk change, except that a plot
     * that couldn't move is an error here rather than a skip: a reserved or
     * sold plot is a 409, an unknown one a 404.
     */
    @Transactional
    public PlotStatusChangeDto changePlotStatus(UUID estateId, UUID plotId, ChangePlotStatusRequest request) {
        PlotStatusChangeDto result = changeStatuses(estateId, List.of(plotId), request.status(), request.reason(), false);
        if (result.changed().isEmpty()) {
            PlotStatusChangeDto.Skipped skipped = result.skipped().getFirst();
            switch (skipped.code()) {
                case "NOT_FOUND" -> throw new InventoryException.RelatedRecordNotFound(
                        "Plot " + plotId + " does not belong to this estate.");
                case "RESERVED", "SOLD" -> throw new InventoryException.PlotNotEditable(
                        plotRepository.findById(plotId).map(this::plotLabel).orElse("Plot " + skipped.plotNumber()),
                        skipped.currentStatus());
                case "NO_RECORDED_AVAILABILITY" -> throw new InventoryException.InvalidRequest(skipped.reason());
                default -> {
                    // ALREADY: nothing to do, and nothing recorded.
                }
            }
        }
        return result;
    }

    /** IE-8: skip and report. Re-checked at apply time, whatever a dry run said. */
    @Transactional
    public PlotStatusChangeDto changePlotStatuses(UUID estateId, BulkPlotStatusRequest request) {
        return changeStatuses(estateId, request.plotIds().stream().distinct().toList(), request.status(),
                request.reason(), Boolean.TRUE.equals(request.dryRun()));
    }

    private PlotStatusChangeDto changeStatuses(UUID estateId, List<UUID> plotIds, String status, String reason,
                                               boolean dryRun) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        PlotStatusWriter.Target target = targetFor(status);

        List<UUID> changed = statusWriter.apply(estateId, plotIds, target, scope.userId().toString(), dryRun);

        Set<UUID> changedSet = Set.copyOf(changed);
        List<UUID> rest = plotIds.stream().filter(id -> !changedSet.contains(id)).toList();
        Map<UUID, Plot> found = new HashMap<>();
        Map<UUID, Plot> changedPlots = new HashMap<>();
        plotRepository.findAllById(plotIds).stream()
                .filter(p -> p.getEstateId().equals(estateId))
                .forEach(p -> (changedSet.contains(p.getId()) ? changedPlots : found).put(p.getId(), p));

        List<PlotStatusChangeDto.Skipped> skipped = rest.stream()
                .map(id -> skipReason(id, found.get(id), target))
                .toList();

        if (!dryRun && !changed.isEmpty()) {
            List<String> labels = changed.stream().map(id -> plotLabel(changedPlots.get(id))).sorted().toList();
            auditApi.record(AuditEntryRequest.of(
                    scope.userId(), "estate.plot_status_changed", "estate", estateId, estate.getTenantId(),
                    changed.size() + " plot(s) on estate " + estateId + " -> " + status.trim().toLowerCase(Locale.ROOT)
                            + ": " + String.join("; ", labels.size() > 25 ? labels.subList(0, 25) : labels)
                            + (labels.size() > 25 ? "; and " + (labels.size() - 25) + " more" : "")
                            + (skipped.isEmpty() ? "" : "; " + skipped.size() + " skipped")
                            + (reason == null || reason.isBlank() ? "." : ". Reason: " + reason.trim())));
            log.info("{} plot(s) on estate {} -> {} by {}", changed.size(), estateId, status, scope.userId());
        }
        return new PlotStatusChangeDto(status.trim().toLowerCase(Locale.ROOT), plotIds.size(), changed, skipped, dryRun);
    }

    private static PlotStatusWriter.Target targetFor(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase(Locale.ROOT)) {
            case "withheld" -> PlotStatusWriter.Target.WITHHOLD;
            case "available" -> PlotStatusWriter.Target.RESTORE;
            case "available-dev" -> PlotStatusWriter.Target.TO_AVAILABLE_DEV;
            case "available-inv" -> PlotStatusWriter.Target.TO_AVAILABLE_INV;
            default -> throw new InventoryException.InvalidRequest("Status must be 'withheld', 'available', "
                    + "'available-dev' or 'available-inv'. Reserved and sold are reached only through checkout.");
        };
    }

    private PlotStatusChangeDto.Skipped skipReason(UUID id, Plot plot, PlotStatusWriter.Target target) {
        if (plot == null) {
            return new PlotStatusChangeDto.Skipped(id, null, null, "NOT_FOUND", "Not a plot on this estate.");
        }
        String status = plot.getStatus().getValue();
        String label = plotLabel(plot);
        return switch (plot.getStatus()) {
            case RESERVED -> new PlotStatusChangeDto.Skipped(id, plot.getPlotNumber(), status, "RESERVED",
                    label + " is held by a buyer; it can't be changed until the hold ends.");
            case SOLD -> new PlotStatusChangeDto.Skipped(id, plot.getPlotNumber(), status, "SOLD",
                    label + " is sold.");
            case WITHHELD -> target == PlotStatusWriter.Target.RESTORE && plot.getWithheldFromStatus() == null
                    ? new PlotStatusChangeDto.Skipped(id, plot.getPlotNumber(), status, "NO_RECORDED_AVAILABILITY",
                    label + " has no recorded availability to return to. Use 'available-dev' or 'available-inv'.")
                    : new PlotStatusChangeDto.Skipped(id, plot.getPlotNumber(), status, "ALREADY",
                    label + " is already " + status + ".");
            default -> new PlotStatusChangeDto.Skipped(id, plot.getPlotNumber(), status, "ALREADY",
                    label + " is already " + status + ".");
        };
    }

    // --- tier retirement (IE-5) ---

    /**
     * Stops new plots joining the tier. Existing plots keep it — available
     * ones stay sellable at its price; taking them off the market is what
     * {@code withheld} is for. Already retired is a no-op with no audit.
     */
    @Transactional
    public PriceTierDto retireTier(UUID estateId, UUID tierId, boolean retire) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        PriceTier tier = requireTier(estateId, tierId);
        if (retire == (tier.getRetiredAt() != null)) {
            return toDto(tier);
        }
        tier.setRetiredAt(retire ? Instant.now() : null);
        tier = priceTierRepository.saveAndFlush(tier);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), retire ? "estate.price_tier_retired" : "estate.price_tier_reinstated",
                "price_tier", tierId, estate.getTenantId(),
                "Tier '" + nullToEmpty(tier.getLabel()) + "' on estate " + estateId
                        + (retire ? " retired: no new plots may join it." : " reinstated.")));
        return toDto(tier);
    }

    // --- withdrawing a plot (IE-11) ---

    /**
     * Soft-deletes a plot that has never been touched — no reservation or
     * transaction in any state, and never part of a conflict record. Anything
     * with history is refused: withdrawing it would orphan the records that
     * refer to it, and {@code withheld} already takes it off the market.
     * Locked first, so a buyer can't reserve it mid-withdrawal.
     */
    @Transactional
    public void withdrawPlot(UUID estateId, UUID plotId) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        Plot plot = plotRepository.findForUpdate(plotId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Plot " + plotId + " does not belong to this estate."));
        if (plot.getStatus() == PlotStatus.RESERVED || plot.getStatus() == PlotStatus.SOLD) {
            throw new InventoryException.PlotNotEditable(plotLabel(plot), plot.getStatus().getValue());
        }
        if (plotHistory.hasPurchaseHistory(plotId) || conflictDetection.hasConflictHistory(plotId)) {
            throw new InventoryException.PlotHasHistory(plotLabel(plot));
        }

        plot.setDeleted(true);
        plotRepository.saveAndFlush(plot);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.plot_withdrawn", "plot", plotId, estate.getTenantId(),
                plotLabel(plot) + " withdrawn from estate " + estateId + "; it had no purchase or conflict history."));
        log.info("Plot {} withdrawn from estate {} by {}", plotId, estateId, scope.userId());
    }

    // --- shared ---

    /**
     * Counts for one tier, by status wire value — only statuses that occur,
     * as {@link PlotCountsDto} requires. {@code excludeAvailable} gives the
     * plots a size change deliberately left alone.
     */
    private PlotCountsDto countsForTier(UUID tierId, boolean excludeAvailable) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : plotRepository.countGroupedByStatusForTier(tierId)) {
            PlotStatus status = (PlotStatus) row[0];
            if (excludeAvailable && AVAILABLE.contains(status)) {
                continue;
            }
            long count = ((Number) row[1]).longValue();
            byStatus.put(status.getValue(), count);
            total += count;
        }
        return new PlotCountsDto(total, byStatus);
    }

    private PriceTier requireTier(UUID estateId, UUID tierId) {
        return priceTierRepository.findByIdAndEstateId(tierId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Price tier " + tierId + " does not belong to this estate."));
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.InvalidRequest(
                    "This endpoint belongs to a tenant's own portal; the caller has no tenant scope.");
        }
        return scope.tenantId();
    }

    /** For writes — applies the EB-2 branch rule. Reads use {@link #requireVisibleEstate}. */
    private Estate requireOwnedEstate(UUID estateId, UUID tenantId) {
        Estate estate = requireVisibleEstate(estateId, tenantId);
        EstateWriteAccess.requireWritable(estate);
        return estate;
    }

    private Estate requireVisibleEstate(UUID estateId, UUID tenantId) {
        return estateRepository.findByIdAndTenantId(estateId, tenantId)
                .orElseThrow(InventoryException.EstateNotFound::new);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static PriceTierDto toDto(PriceTier tier) {
        return new PriceTierDto(tier.getId(), tier.getEstateId(), tier.getTierType().getValue(),
                tier.getSizeSqm(), tier.getPrice(), tier.getCurrency(), tier.getLabel(), tier.getRetiredAt());
    }

    private static BlockDto toDto(Block block) {
        return new BlockDto(block.getId(), block.getEstateId(), block.getName(), block.getLabel());
    }
}
