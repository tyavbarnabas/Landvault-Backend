package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateAmenity;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateVerificationCheck;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.inventory.internal.enums.EstateIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.ListingIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotOrientation;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PropertyType;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TierType;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TitleType;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationCheckStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationCheckType;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationSource;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Creating estates and everything under them. Creation only — reads are
 * slice 3, conflict detection is slice 4.
 * <p>
 * Two rules run through every method here and are worth stating once:
 * <strong>tenant comes from {@link TenantContext}, never the request body</strong>
 * (accepting one would be a cross-tenant breach), and every write records
 * through {@link AuditApi}.
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

        Estate estate = Estate.builder()
                .name(request.name())
                .slug(slug)
                .description(request.description())
                .area(request.area())
                .city(request.city())
                .state(request.state())
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
        estate = estateRepository.save(estate);

        List<String> amenities = saveAmenities(estate, request.amenities());

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.created", "estate", estate.getId(), tenantId,
                "Estate '" + estate.getName() + "' created" + (footprint == null ? " without a boundary." : " with a boundary.")));
        log.info("Estate created: {} (id={}, tenant={}, boundary={})",
                estate.getName(), estate.getId(), tenantId, footprint != null);

        return toDto(estate, amenities);
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
        List<Plot> saved = plotRepository.saveAll(plots);

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
                .propertyType(request.propertyType() == null
                        ? PropertyType.LAND : PropertyType.fromValue(request.propertyType()))
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
            throw new InventoryException.InvalidRequest(
                    "branchId is required: an estate belongs to a branch, and your role is organization-wide.");
        }
        if (!tenancyApi.branchBelongsToTenant(requestedBranchId, tenantId)) {
            throw new InventoryException.InvalidRequest("That branch does not belong to your organization.");
        }
        return requestedBranchId;
    }

    private Estate requireOwnedEstate(UUID estateId, UUID tenantId) {
        return estateRepository.findByIdAndTenantId(estateId, tenantId)
                .orElseThrow(InventoryException.EstateNotFound::new);
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
                tier.getSizeSqm(), tier.getPrice(), tier.getCurrency(), tier.getLabel());
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
