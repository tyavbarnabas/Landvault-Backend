package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.conflicts.ConflictDetectionApi;
import com.techcomfort.landvaultbackend.conflicts.ConflictPublicationCheck;
import com.techcomfort.landvaultbackend.marketplace.EstateEligibility;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.AddEstateBoundaryRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.UpdateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateBoundaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.PublicationDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateAmenity;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateVerificationCheck;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.ListingIntent;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotOrientation;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PropertyType;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TierType;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.common.VerificationCheckStatus;
import com.techcomfort.landvaultbackend.common.VerificationCheckType;
import com.techcomfort.landvaultbackend.common.VerificationSource;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateAmenityRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateTitleRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateVerificationCheckRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PriceTierRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Creating estates and everything under them. Reads live in
 * {@code PortalEstateQueryService}.
 * <p>
 * Three rules run through every method here and are worth stating once:
 * <strong>tenant comes from {@link TenantContext}, never the request body</strong>
 * (accepting one would be a cross-tenant breach), every write records
 * through {@link AuditApi}, and <strong>every footprint written triggers
 * conflict detection in the same transaction</strong> (CD-4) — so an estate
 * and the overlaps it raises commit or roll back together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalEstateService {

    private final EstateRepository estateRepository;
    private final EstateAmenityRepository estateAmenityRepository;
    private final BlockRepository blockRepository;
    private final PriceTierRepository priceTierRepository;
    private final PlotRepository plotRepository;
    private final EstateTitleRepository estateTitleRepository;
    private final EstateVerificationCheckRepository verificationCheckRepository;
    private final GeoJsonPolygonParser geoJsonParser;
    private final GeometryCalculator geometry;
    private final TenancyApi tenancyApi;
    private final AuditApi auditApi;
    private final ConflictDetectionApi conflictDetection;
    private final MarketplaceApi marketplace;
    private final StateBoundaryService stateBoundaries;

    // --- estate ---

    @Transactional
    public EstateDto createEstate(CreateEstateRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireTenant(scope);
        UUID branchId = resolveBranch(scope, tenantId, request.branchId());

        String slug = slugify(request.name());
        if (estateRepository.existsByTenantIdAndSlugIgnoreCase(tenantId, slug)) {
            throw new InventoryException.DuplicateRecord(
                    "An estate with a name matching '" + slug + "' already exists for this tenant.");
        }

        Polygon footprint = geoJsonParser.parse(request.footprint(), "footprint");
        StateBoundaryService.ResolvedState state = stateBoundaries.resolve(request.state());

        Estate estate = Estate.builder()
                .name(request.name())
                .slug(slug)
                .description(request.description())
                .area(request.area())
                .city(request.city())
                .state(state.name())
                .stateCode(state.code())
                .address(request.address())
                .cornerPremiumPct(request.cornerPremiumPct())
                .intent(request.intent() == null ? null : EstateIntent.fromValue(request.intent()))
                .footprint(footprint)
                // Publication is a separate deliberate action, never a
                // creation-time flag. See AGENTS.md's four-condition gate.
                .published(false)
                .build();
        estate.setTenantId(tenantId);
        estate.setBranchId(branchId);
        // SB-1: before anything is written — a boundary in the wrong state is
        // a data error caught at the door, like a plot outside its estate.
        stateBoundaries.requireWithinDeclaredState(footprint, estate);
        // saveAndFlush, not save: detection runs next and needs this row
        // visible. Flushing through the repository rather than letting
        // detection's own EntityManager.flush() do it keeps Spring's
        // persistence-exception translation in play, so a constraint
        // violation still arrives as DataIntegrityViolationException (409)
        // rather than a raw PersistenceException (500).
        estate = estateRepository.saveAndFlush(estate);

        List<String> amenities = saveAmenities(estate, request.amenities());

        // CD-4: detect at the point the boundary is submitted, not at
        // publication. Catching an overlap later would mean unwinding work
        // already done — plots priced, listings prepared, possibly deposits
        // taken. Runs in this same transaction, so an estate and the
        // conflicts it raises commit or roll back together.
        int conflicts = conflictDetection.detectForEstateBoundary(estate.getId());
        if (conflicts > 0) {
            log.info("Estate {} raised {} boundary conflict(s) on creation", estate.getId(), conflicts);
        }

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.created", "estate", estate.getId(), tenantId,
                "Estate '" + estate.getName() + "' created" + (footprint == null ? " without a boundary." : " with a boundary.")));
        log.info("Estate created: {} (id={}, tenant={}, boundary={})",
                estate.getName(), estate.getId(), tenantId, footprint != null);

        return toDto(estate, amenities);
    }

    /**
     * Adds a boundary to an estate created without one — the way back for an
     * estate BG-1 keeps off the marketplace. Only when there is no boundary
     * yet: changing an existing one would need its own design (re-checking
     * every plot and every overlap it used to satisfy), and is not offered.
     * <p>
     * Same order as creation, and the same transaction: parse, check every
     * existing plot sits inside, save, then run overlap detection, so an
     * estate gaining a boundary is compared against other companies' land
     * before it can be listed. An already-published estate returns to the
     * marketplace on its own if it now passes every condition; a HIGH
     * overlap keeps it off, and the response says so.
     */
    @Transactional
    public EstateBoundaryDto addBoundary(UUID estateId, AddEstateBoundaryRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        if (estate.getFootprint() != null) {
            throw new InventoryException.BoundaryAlreadySet();
        }

        Polygon footprint = geoJsonParser.parse(request.footprint(), "footprint");
        if (footprint == null) {
            throw new InventoryException.InvalidGeometry("footprint", "A boundary is required.");
        }

        stateBoundaries.requireWithinDeclaredState(footprint, estate);

        List<String> outside = geometry.plotsOutside(estateId, footprint);
        if (!outside.isEmpty()) {
            throw new InventoryException.PlotOutsideEstate(
                    "These plots fall outside the boundary you've supplied: " + String.join("; ", outside)
                            + ". Check the boundary, or correct those plots first.");
        }

        estate.setFootprint(footprint);
        // Through the repository, so a constraint violation is still
        // translated rather than escaping as a 500 — see AGENTS.md.
        estateRepository.saveAndFlush(estate);

        int conflicts = conflictDetection.detectForEstateBoundary(estateId);
        ConflictPublicationCheck check = conflictDetection.publicationCheckFor(estateId);
        BigDecimal area = geometry.areaInSquareMetres(footprint);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.boundary_added", "estate", estateId, estate.getTenantId(),
                "Boundary added to estate '" + estate.getName() + "' (" + area + " sqm); "
                        + conflicts + " live conflict(s) after detection."));
        log.info("Boundary added to estate {} by actor {}; {} live conflict(s)", estateId, scope.userId(), conflicts);

        return new EstateBoundaryDto(estateId, area, check.blocked(), check.blockReason(), check.warningConflictCount());
    }

    /**
     * Edits an estate's plain fields. Left out means unchanged; an edit that
     * changes nothing writes nothing. Three fields are refused rather than
     * ignored, because each carries consequences a plain edit must not: the
     * boundary (overlap detection, BG-1 — its own route), publication (the
     * eligibility gate — its own routes) and the branch (every plot's RLS
     * branch wall — not supported).
     * <p>
     * A rename regenerates the slug, with the same per-tenant uniqueness as
     * creation. A corner-premium change reprices every corner plot at once
     * (prices are computed on read); buyers who already reserved keep the
     * price captured on their reservation.
     */
    @Transactional
    public EstateDto updateEstate(UUID estateId, UpdateEstateRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireTenant(scope);
        Estate estate = requireOwnedEstate(estateId, tenantId);

        if (request.footprint() != null) {
            throw new InventoryException.ImmutableField("BOUNDARY_NOT_EDITABLE_HERE",
                    "An estate's boundary is added with POST /api/portal/estates/{id}/boundary, which runs the "
                            + "overlap check. It can't be changed through an estate update.");
        }
        if (request.published() != null) {
            throw new InventoryException.ImmutableField("PUBLICATION_NOT_EDITABLE_HERE",
                    "Use POST /api/portal/estates/{id}/publish or /unpublish — publishing checks every condition.");
        }
        if (request.branchId() != null && !request.branchId().equals(estate.getBranchId())) {
            throw new InventoryException.ImmutableField("BRANCH_NOT_EDITABLE",
                    "Moving an estate to another branch isn't supported.");
        }

        List<String> changes = new ArrayList<>();

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new InventoryException.InvalidRequest("An estate name cannot be blank.");
            }
            String name = request.name().trim();
            if (!name.equals(estate.getName())) {
                String slug = slugify(name);
                if (!slug.equalsIgnoreCase(estate.getSlug())
                        && estateRepository.existsByTenantIdAndSlugIgnoreCaseAndIdNot(tenantId, slug, estateId)) {
                    throw new InventoryException.DuplicateRecord(
                            "An estate with a name matching '" + slug + "' already exists for this tenant.");
                }
                changes.add("name '" + estate.getName() + "' -> '" + name + "'");
                estate.setName(name);
                estate.setSlug(slug);
            }
        }
        if (request.state() != null) {
            if (request.state().isBlank()) {
                throw new InventoryException.InvalidRequest("An estate's state cannot be cleared.");
            }
            StateBoundaryService.ResolvedState state = stateBoundaries.resolve(request.state());
            if (!state.code().equals(estate.getStateCode())) {
                changes.add("state '" + estate.getState() + "' -> '" + state.name() + "'");
                estate.setState(state.name());
                estate.setStateCode(state.code());
                // An override verified the OLD state; it says nothing about the new one.
                estate.setStateOverrideAt(null);
                estate.setStateOverrideBy(null);
                estate.setStateOverrideReason(null);
                // SB-1: the existing boundary must sit inside the newly declared state.
                stateBoundaries.requireWithinDeclaredState(estate.getFootprint(), estate);
            }
        }
        changeText(changes, "description", estate.getDescription(), request.description(), estate::setDescription);
        changeText(changes, "area", estate.getArea(), request.area(), estate::setArea);
        changeText(changes, "city", estate.getCity(), request.city(), estate::setCity);
        changeText(changes, "address", estate.getAddress(), request.address(), estate::setAddress);

        if (request.cornerPremiumPct() != null && (estate.getCornerPremiumPct() == null
                || request.cornerPremiumPct().compareTo(estate.getCornerPremiumPct()) != 0)) {
            changes.add("corner premium " + (estate.getCornerPremiumPct() == null ? "none"
                    : estate.getCornerPremiumPct().toPlainString() + "%") + " -> "
                    + request.cornerPremiumPct().toPlainString() + "%");
            estate.setCornerPremiumPct(request.cornerPremiumPct());
        }
        if (request.intent() != null) {
            EstateIntent intent = request.intent().isBlank() ? null : EstateIntent.fromValue(request.intent());
            if (intent != estate.getIntent()) {
                changes.add("intent " + (estate.getIntent() == null ? "none" : estate.getIntent().getValue())
                        + " -> " + (intent == null ? "none" : intent.getValue()));
                estate.setIntent(intent);
            }
        }

        List<EstateAmenity> current = estateAmenityRepository.findByEstateIdOrderByNameAsc(estateId);
        List<String> existingAmenities = current.stream().map(EstateAmenity::getName).toList();
        List<String> amenities = existingAmenities;
        if (request.amenities() != null) {
            List<String> wanted = request.amenities().stream()
                    .filter(a -> a != null && !a.isBlank()).map(String::trim).distinct().toList();
            if (!Set.copyOf(wanted).equals(Set.copyOf(existingAmenities))) {
                // Hard-deleted: an amenity is a description, not history, and
                // the (estate, name) unique constraint counts soft-deleted rows,
                // so a soft-deleted one could never be added back.
                estateAmenityRepository.deleteAll(current.stream().filter(a -> !wanted.contains(a.getName())).toList());
                estateAmenityRepository.flush();
                saveAmenities(estate, wanted.stream().filter(a -> !existingAmenities.contains(a)).toList());
                changes.add("amenities " + existingAmenities + " -> " + wanted);
                amenities = wanted;
            }
        }

        if (changes.isEmpty()) {
            return toDto(estate, amenities);
        }
        // saveAndFlush: a lost race on the slug's unique constraint surfaces
        // here as a translated 409 rather than at commit.
        estateRepository.saveAndFlush(estate);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.updated", "estate", estateId, estate.getTenantId(),
                "Estate '" + estate.getName() + "' changed: " + String.join("; ", changes) + "."));
        log.info("Estate {} updated by {}", estateId, scope.userId());
        return toDto(estate, amenities);
    }

    /** Null leaves it; blank clears it; anything else is trimmed and set if different. */
    private static void changeText(List<String> changes, String field, String current, String requested,
                                   java.util.function.Consumer<String> setter) {
        if (requested == null) {
            return;
        }
        String value = requested.isBlank() ? null : requested.trim();
        if (!java.util.Objects.equals(value, current)) {
            changes.add(field + " '" + (current == null ? "" : current) + "' -> '" + (value == null ? "" : value) + "'");
            setter.accept(value);
        }
    }

    // --- block ---

    @Transactional
    public BlockDto createBlock(UUID estateId, CreateBlockRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));

        if (blockRepository.existsByEstateIdAndNameIgnoreCase(estateId, request.name())) {
            throw new InventoryException.DuplicateRecord(
                    "Block '" + request.name() + "' already exists on this estate.");
        }

        Block block = Block.builder()
                .estateId(estateId)
                .name(request.name())
                .label(request.label())
                .build();
        block.setTenantId(estate.getTenantId());
        block.setBranchId(estate.getBranchId());
        block = blockRepository.save(block);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.block_added", "estate", estateId, estate.getTenantId(),
                "Block '" + block.getName() + "' added."));
        return new BlockDto(block.getId(), estateId, block.getName(), block.getLabel());
    }

    // --- price tier ---

    @Transactional
    public PriceTierDto createPriceTier(UUID estateId, CreatePriceTierRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));

        TierType tierType = TierType.fromValue(request.tierType());
        // The database enforces this too; checking here turns a constraint
        // violation into a clean, explicable 400.
        if (tierType == TierType.LAND_SIZE && request.sizeSqm() == null) {
            throw new InventoryException.InvalidRequest(
                    "A LAND_SIZE tier needs a sizeSqm — a land band without a size is meaningless. "
                            + "Use tierType UNIT_TYPE for a built-unit product, where the label carries the meaning.");
        }
        if (request.sizeSqm() != null && priceTierRepository.existsByEstateIdAndSizeSqm(estateId, request.sizeSqm())) {
            throw new InventoryException.DuplicateRecord(
                    "A tier for " + request.sizeSqm() + " sqm already exists on this estate.");
        }

        PriceTier tier = PriceTier.builder()
                .estateId(estateId)
                .tierType(tierType)
                .sizeSqm(request.sizeSqm())
                .price(request.price())
                .currency(request.currency())
                .label(request.label())
                .build();
        tier.setTenantId(estate.getTenantId());
        tier.setBranchId(estate.getBranchId());
        tier = priceTierRepository.save(tier);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.price_tier_added", "estate", estateId, estate.getTenantId(),
                "Price tier added: " + (tier.getSizeSqm() == null ? tier.getLabel() : tier.getSizeSqm() + " sqm")
                        + " at " + tier.getCurrency() + " " + tier.getPrice() + "."));
        return toDto(tier);
    }

    // --- plots (batch) ---

    @Transactional
    public List<PlotDto> createPlots(UUID estateId, CreatePlotsRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));

        requireDistinctPlotNumbersWithinBatch(request);

        List<Plot> plots = request.plots().stream()
                .map(plotRequest -> buildPlot(estate, plotRequest))
                .toList();
        // saveAllAndFlush for the same reason as createEstate: a duplicate
        // plot number must surface here, translated, before detection runs.
        List<Plot> saved = plotRepository.saveAllAndFlush(plots);

        // CD-2: within-estate plot overlap — the more common version of the
        // scam, and undetectable before plots carried geometry. Runs across
        // the whole estate rather than only the new batch, since a new plot
        // can overlap one added months ago.
        int conflicts = conflictDetection.detectForEstatePlots(estateId);
        if (conflicts > 0) {
            log.info("Estate {} has {} live plot conflict(s) after adding {} plot(s)",
                    estateId, conflicts, saved.size());
        }

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.plots_added", "estate", estateId, estate.getTenantId(),
                saved.size() + " plot(s) added."));
        log.info("Added {} plot(s) to estate {}", saved.size(), estateId);

        return saved.stream().map(PortalEstateService::toDto).toList();
    }

    /**
     * A batch that repeats a plot number within itself would hit the database
     * constraint mid-insert and surface as an opaque conflict naming only the
     * second occurrence. Caught here so the caller is told plainly, before
     * anything is written.
     * <p>
     * Uniqueness against plots <em>already</em> in the estate is still the
     * database's job — checking it here would be a read per plot, and the
     * constraint is authoritative anyway.
     */
    private static void requireDistinctPlotNumbersWithinBatch(CreatePlotsRequest request) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = request.plots().stream()
                .map(plot -> (plot.blockId() == null ? "" : plot.blockId() + "/") + plot.plotNumber())
                .filter(key -> !seen.add(key))
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new InventoryException.InvalidRequest(
                    "This batch repeats plot number(s) " + duplicates + ". Plot numbers are unique per block, "
                            + "and per estate for plots with no block.");
        }
    }

    private Plot buildPlot(Estate estate, CreatePlotRequest request) {
        PriceTier tier = priceTierRepository.findByIdAndEstateId(request.priceTierId(), estate.getId())
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Price tier " + request.priceTierId() + " does not belong to this estate."));

        if (tier.getRetiredAt() != null) {
            throw new InventoryException.ImmutableField("TIER_RETIRED",
                    "Plot " + request.plotNumber() + ": tier '" + (tier.getLabel() == null ? tier.getId() : tier.getLabel())
                            + "' is retired and accepts no new plots.");
        }
        if (request.blockId() != null
                && blockRepository.findByIdAndEstateId(request.blockId(), estate.getId()).isEmpty()) {
            throw new InventoryException.RelatedRecordNotFound(
                    "Block " + request.blockId() + " does not belong to this estate.");
        }

        Polygon footprint = geoJsonParser.parse(request.footprint(), "plots[" + request.plotNumber() + "].footprint");
        if (footprint != null && estate.getFootprint() != null
                && !geometry.isWithin(footprint, estate.getFootprint())) {
            throw new InventoryException.PlotOutsideEstate(
                    "Plot " + request.plotNumber() + "'s boundary falls outside the estate boundary.");
        }

        Plot plot = Plot.builder()
                .estateId(estate.getId())
                .blockId(request.blockId())
                .priceTierId(tier.getId())
                .plotNumber(request.plotNumber())
                .isCorner(Boolean.TRUE.equals(request.isCorner()))
                .status(PlotStatus.fromValue(request.status()))
                .intent(request.intent() == null ? null : PlotIntent.fromValue(request.intent()))
                .propertyType(propertyTypeFor(tier, request))
                .listingIntent(request.listingIntent() == null
                        ? ListingIntent.FOR_SALE : ListingIntent.fromValue(request.listingIntent()))
                .orientation(request.orientation() == null
                        ? null : PlotOrientation.fromValue(request.orientation()))
                .nominalSizeSqm(nominalSizeFor(tier, request))
                .footprint(footprint)
                // Computed on write, never supplied. Square metres via the
                // geography cast — raw 4326 would yield square degrees. Must
                // be recomputed if the footprint is ever edited; see AGENTS.md.
                .actualAreaSqm(geometry.areaInSquareMetres(footprint))
                .build();
        plot.setTenantId(estate.getTenantId());
        plot.setBranchId(estate.getBranchId());
        return plot;
    }

    /**
     * A plot's property type follows its tier: a land-size tier prices bare
     * land, a unit-type tier prices a built product. Derived when the request
     * leaves it out — which is what file import and most callers do — and
     * refused when it contradicts the tier, rather than storing a land plot
     * priced as a 3-bedroom terrace.
     */
    static PropertyType propertyTypeFor(PriceTier tier, CreatePlotRequest request) {
        PropertyType expected = expectedPropertyType(tier);
        if (request.propertyType() == null) {
            return expected;
        }
        PropertyType requested = PropertyType.fromValue(request.propertyType());
        if (requested != expected) {
            throw new InventoryException.ImmutableField("PROPERTY_TYPE_MISMATCH",
                    "Plot " + request.plotNumber() + " is '" + requested.getValue() + "' but its tier is a "
                            + tier.getTierType().getValue() + " tier, which prices "
                            + (expected == PropertyType.LAND ? "bare land" : "a built unit") + ".");
        }
        return requested;
    }

    static PropertyType expectedPropertyType(PriceTier tier) {
        return tier.getTierType() == TierType.UNIT_TYPE ? PropertyType.BUILT : PropertyType.LAND;
    }

    /**
     * The tier's size, always — never the request's, or a caller could price
     * a plot by one tier while selling it as another size.
     * <p>
     * A {@code UNIT_TYPE} tier has no size of its own, so an override is
     * accepted there and only there: a terrace on its own plot has a real
     * land area, an apartment has none and stays null. Never zero.
     */
    private static BigDecimal nominalSizeFor(PriceTier tier, CreatePlotRequest request) {
        if (tier.getTierType() == TierType.LAND_SIZE) {
            return tier.getSizeSqm();
        }
        return request.nominalSizeSqmOverride();
    }

    // --- title ---

    @Transactional
    public EstateTitleDto recordTitle(UUID estateId, CreateEstateTitleRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));

        if (estateTitleRepository.existsByEstateId(estateId)) {
            throw new InventoryException.DuplicateRecord(
                    "This estate already has a title recorded. Title is 1:1 with an estate.");
        }

        EstateTitle title = EstateTitle.builder()
                .estateId(estateId)
                .titleType(TitleType.fromValue(request.titleType()))
                .titleNumber(request.titleNumber())
                .issuedDate(request.issuedDate() == null ? null : LocalDate.parse(request.issuedDate()))
                .surveyPlanDocumentId(request.surveyPlanDocumentId())
                .deedDocumentId(request.deedDocumentId())
                .build();
        title.setTenantId(estate.getTenantId());
        title.setBranchId(estate.getBranchId());
        title = estateTitleRepository.save(title);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.title_recorded", "estate", estateId, estate.getTenantId(),
                "Title recorded: " + title.getTitleType().getValue()
                        + (title.getTitleNumber() == null ? "" : " " + title.getTitleNumber()) + "."));
        return new EstateTitleDto(title.getId(), estateId, title.getTitleType().getValue(),
                title.getTitleNumber(), title.getIssuedDate(),
                title.getSurveyPlanDocumentId(), title.getDeedDocumentId());
    }

    // --- verification check ---

    @Transactional
    public VerificationCheckDto recordVerificationCheck(UUID estateId, CreateVerificationCheckRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));

        VerificationCheckType checkType = VerificationCheckType.fromValue(request.checkType());
        // NOT_CHECKED when omitted: the absence of a check is not a clean
        // bill of health, and must never be recorded as one.
        VerificationCheckStatus status = request.status() == null
                ? VerificationCheckStatus.NOT_CHECKED
                : VerificationCheckStatus.fromValue(request.status());
        VerificationSource source = request.verificationSource() == null
                ? null : VerificationSource.fromValue(request.verificationSource());

        if (status == VerificationCheckStatus.VERIFIED && source == null) {
            throw new InventoryException.InvalidRequest(
                    "A VERIFIED check needs a verificationSource — a green badge from a registry lookup and one "
                            + "from a human reading a PDF are different claims, and must stay distinguishable.");
        }

        EstateVerificationCheck check = verificationCheckRepository
                .findByEstateIdAndCheckType(estateId, checkType)
                .orElseGet(() -> {
                    EstateVerificationCheck fresh = EstateVerificationCheck.builder()
                            .estateId(estateId)
                            .checkType(checkType)
                            .build();
                    fresh.setTenantId(estate.getTenantId());
                    fresh.setBranchId(estate.getBranchId());
                    return fresh;
                });
        check.setStatus(status);
        check.setVerificationSource(source);
        check.setNotes(request.notes());
        check.setLastVerifiedAt(status == VerificationCheckStatus.VERIFIED ? Instant.now() : null);
        check = verificationCheckRepository.save(check);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.verification_check_recorded", "estate", estateId, estate.getTenantId(),
                checkType.getValue() + " -> " + status.getValue()
                        + (source == null ? "" : " (" + source.getValue() + ")")));
        return new VerificationCheckDto(check.getId(), estateId, checkType.getValue(), status.getValue(),
                source == null ? null : source.getValue(), check.getLastVerifiedAt(), check.getNotes());
    }

    // --- publication (PB-1..PB-4) ---

    /**
     * Puts an estate on the marketplace: a deliberate action, never a side
     * effect of creating or editing one (PB-1).
     * <p>
     * All five conditions are checked, and every failing one is named
     * (PB-3). The tenant conditions come from {@code MarketplaceApi}, which
     * reads the same view the public feed filters on; the conflict condition
     * from {@code ConflictDetectionApi}, which calls the same SQL function
     * that view does. So "refused here" and "missing from the feed" are the
     * same answer, not two implementations that could drift.
     * <p>
     * Conditions are checked even when the estate is already published. A
     * published estate can be off the marketplace (its tenant was suspended,
     * a conflict arrived since), and publishing again is exactly how a
     * developer finds out why.
     */
    @Transactional
    public PublicationDto publish(UUID estateId) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        EstateEligibility eligibility = marketplace.eligibilityOf(estateId)
                .orElseThrow(InventoryException.EstateNotFound::new);
        ConflictPublicationCheck conflicts = conflictDetection.publicationCheckFor(estateId);

        requirePublishable(eligibility, conflicts);

        if (!Boolean.TRUE.equals(estate.getPublished())) {
            estate.setPublished(true);
            estate.setPublishedAt(Instant.now());
            estate = estateRepository.save(estate);
            auditApi.record(AuditEntryRequest.of(
                    scope.userId(), "estate.published", "estate", estateId, estate.getTenantId(),
                    "Estate '" + estate.getName() + "' published to the marketplace"
                            + (conflicts.warningConflictCount() > 0
                            ? " with " + conflicts.warningConflictCount() + " unresolved same-company overlap(s)."
                            : ".")));
            log.info("Estate published: {} by actor {}", estateId, scope.userId());
        }
        // An already-published estate that passes is a no-op: nothing
        // happened, so nothing is audited.

        return new PublicationDto(estateId, true, estate.getPublishedAt(),
                conflicts.warningConflictCount(),
                conflicts.warningConflictCount() > 0
                        ? "Some of this company's own boundaries overlap each other. That doesn't stop "
                        + "publication, but correcting the survey coordinates is worth doing."
                        : null);
    }

    /**
     * Pulls one listing without involving the platform or touching tenant
     * status (PB-4). The estate and its plots are untouched; only the
     * developer's intent changes. Already unpublished is a no-op.
     */
    @Transactional
    public PublicationDto unpublish(UUID estateId) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));

        if (Boolean.TRUE.equals(estate.getPublished())) {
            estate.setPublished(false);
            // published_at means "live since"; it is not a history. The audit
            // log holds when this estate was published and pulled.
            estate.setPublishedAt(null);
            estateRepository.save(estate);
            auditApi.record(AuditEntryRequest.of(
                    scope.userId(), "estate.unpublished", "estate", estateId, estate.getTenantId(),
                    "Estate '" + estate.getName() + "' taken off the marketplace."));
            log.info("Estate unpublished: {} by actor {}", estateId, scope.userId());
        }
        return new PublicationDto(estateId, false, null, 0, null);
    }

    /**
     * Factual, never accusatory: a conflict is usually a survey error, and
     * its message comes from {@code ConflictPublicationCheck}, which carries
     * no counterparty identity by design (CD-11).
     */
    private static void requirePublishable(EstateEligibility eligibility, ConflictPublicationCheck conflicts) {
        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (!eligibility.tenantVerified()) {
            codes.add("PUBLICATION_VERIFICATION_PENDING");
            reasons.add("Your company's verification isn't complete yet; estates can go on the marketplace "
                    + "once it's approved.");
        }
        if (!eligibility.tenantEntitled()) {
            codes.add("PUBLICATION_ENTITLEMENT_MISSING");
            reasons.add("Your plan doesn't include marketplace publishing.");
        }
        if (!eligibility.tenantActive()) {
            codes.add("PUBLICATION_TENANT_NOT_ACTIVE");
            reasons.add("Your company's account isn't active, so listings can't be published right now.");
        }
        if (!eligibility.feesDeclared()) {
            // The condition this platform adds that the market does not: a
            // listing whose true cost is undeclared is exactly the listing a
            // buyer cannot evaluate.
            codes.add("PUBLICATION_FEES_UNDECLARED");
            reasons.add("Declare this estate's fee schedule before listing it. Declaring that there "
                    + "are no charges beyond the land price counts — saying nothing does not.");
        }
        if (!eligibility.refundTermsDeclared()) {
            // RF-4. Developers sometimes object that their terms look harsh
            // beside a competitor's; the terms are identical either way, and
            // only one platform says so beforehand.
            codes.add("PUBLICATION_REFUND_TERMS_UNDECLARED");
            reasons.add("Declare what a buyer gets back if they withdraw, and how long it takes, "
                    + "before listing this estate.");
        }
        if (!eligibility.hasBoundary()) {
            // BG-1. Before the conflict check because it is what makes that
            // check meaningful: with no boundary there is nothing to compare,
            // and "no conflict" would be an absence of evidence.
            codes.add("PUBLICATION_BOUNDARY_MISSING");
            reasons.add("Add this estate's boundary before listing it. Without one, it can't be checked "
                    + "against neighbouring land, and buyers can't see where it is.");
        }
        if (!eligibility.hasPlots()) {
            codes.add("PUBLICATION_NO_PLOTS");
            reasons.add("Add this estate's plots before listing it — buyers need something to choose from.");
        }
        if (conflicts.blocked()) {
            codes.add("PUBLICATION_CONFLICT_OUTSTANDING");
            reasons.add(conflicts.blockReason());
        }
        if (!codes.isEmpty()) {
            throw new InventoryException.PublicationRefused(codes.getFirst(), String.join(" ", reasons));
        }
    }

    // --- shared ---

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    /**
     * Platform staff have no tenant of their own, so they have no estate to
     * create one under. This is a portal surface, not an admin one.
     */
    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.InvalidRequest(
                    "This endpoint belongs to a tenant's own portal; the caller has no tenant scope.");
        }
        return scope.tenantId();
    }

    /**
     * A branch-scoped caller gets theirs; an organization-wide caller must
     * name one, and it is checked against their own tenant via
     * {@code TenancyApi} — never trusted from the request alone.
     */
    private UUID resolveBranch(TenantScope scope, UUID tenantId, UUID requestedBranchId) {
        if (scope.branchId() != null) {
            return scope.branchId();
        }
        if (requestedBranchId == null) {
            // EB-1: no branch means the estate belongs to the organisation
            // directly — a single-office developer has no branch to name, and
            // inventing a "Head Office" would be a fiction. Branch-scoped
            // users never reach here: their estates go to their own branch.
            return null;
        }
        if (!tenancyApi.branchBelongsToTenant(requestedBranchId, tenantId)) {
            throw new InventoryException.InvalidRequest("That branch does not belong to your organization.");
        }
        return requestedBranchId;
    }

    /** Every caller writes, so the EB-2 branch rule is applied here once. */
    private Estate requireOwnedEstate(UUID estateId, UUID tenantId) {
        Estate estate = estateRepository.findByIdAndTenantId(estateId, tenantId)
                .orElseThrow(InventoryException.EstateNotFound::new);
        EstateWriteAccess.requireWritable(estate);
        return estate;
    }

    private List<String> saveAmenities(Estate estate, List<String> amenities) {
        if (amenities == null || amenities.isEmpty()) {
            return List.of();
        }
        List<String> distinct = amenities.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        estateAmenityRepository.saveAll(distinct.stream()
                .map(name -> {
                    EstateAmenity amenity = EstateAmenity.builder()
                            .estateId(estate.getId())
                            .name(name)
                            .build();
                    amenity.setTenantId(estate.getTenantId());
                    amenity.setBranchId(estate.getBranchId());
                    return amenity;
                })
                .toList());
        return distinct;
    }

    // Lowercase, hyphenated, no runs of separators — unique per tenant, not
    // globally.
    private static String slugify(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "estate" : slug;
    }

    private EstateDto toDto(Estate estate, List<String> amenities) {
        return new EstateDto(
                estate.getId(), estate.getTenantId(), estate.getBranchId(),
                estate.getName(), estate.getSlug(), estate.getDescription(),
                estate.getArea(), estate.getCity(), estate.getState(), estate.getAddress(),
                estate.getCornerPremiumPct(),
                estate.getIntent() == null ? null : estate.getIntent().getValue(),
                amenities,
                Boolean.TRUE.equals(estate.getPublished()), estate.getPublishedAt(),
                estate.getFootprint() != null,
                geometry.areaInSquareMetres(estate.getFootprint()),
                estate.getCreatedAt());
    }

    private static PriceTierDto toDto(PriceTier tier) {
        return new PriceTierDto(tier.getId(), tier.getEstateId(), tier.getTierType().getValue(),
                tier.getSizeSqm(), tier.getPrice(), tier.getCurrency(), tier.getLabel(), tier.getRetiredAt());
    }

    private static PlotDto toDto(Plot plot) {
        return new PlotDto(
                plot.getId(), plot.getEstateId(), plot.getBlockId(), plot.getPriceTierId(),
                plot.getPlotNumber(), Boolean.TRUE.equals(plot.getIsCorner()),
                plot.getStatus().getValue(),
                plot.getIntent() == null ? null : plot.getIntent().getValue(),
                plot.getPropertyType().getValue(),
                plot.getListingIntent().getValue(),
                plot.getOrientation() == null ? null : plot.getOrientation().getValue(),
                plot.getNominalSizeSqm(), plot.getActualAreaSqm(),
                plot.getFootprint() != null);
    }
}
