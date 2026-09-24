package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateSummaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.PublicationDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.internal.service.PortalEstateQueryService;
import com.techcomfort.landvaultbackend.inventory.internal.service.PortalEstateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A tenant's own estate inventory — creation and reads. Conflict detection
 * is slice 4.
 * <p>
 * <strong>Two permissions, not one.</strong> Writes need
 * {@code portal.estates.manage}; reads need only
 * {@code portal.estates.view}, which is granted far more widely (sales,
 * finance, legal, branch managers) because looking at the inventory is what
 * most of a developer's staff do and defining it is not. Neither implies
 * the other — see changeset 045.
 * <p>
 * The tenant an estate belongs to comes from the authenticated caller's
 * scope, never from a request body, and which rows a read returns is
 * decided by row-level security (changeset 044), not by a filter in the
 * repository; see {@code PortalEstateQueryService}.
 */
@RestController
@RequestMapping("/api/portal/estates")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_ESTATES)
public class PortalEstateController {

    private static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * A real estate runs to hundreds of plots, so an uncapped {@code limit}
     * is a way to ask for all of them in one response. Capped rather than
     * left to the caller, same as the audit log.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final PortalEstateService service;
    private final PortalEstateQueryService queryService;

    // --- reads ---

    @Operation(summary = "List your own estates",
            description = """
                    Requires `portal.estates.view`.

                    **Which estates come back is decided by the database, not by this endpoint.** \
                    Row-level security scopes every read to the caller's company, and a \
                    branch-scoped user (a branch manager) sees only their own branch's estates. \
                    There is no parameter that widens that. `branchId` only narrows an \
                    organization-wide caller further.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of estates, newest first")
    })
    @GetMapping
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<PageResponse<EstateSummaryDto>> listEstates(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Boolean published,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {

        return ResponseEntity.ok(queryService.listEstates(
                state, published, intent, branchId, q,
                pageable(cursor, limit, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @Operation(summary = "One estate in full",
            description = """
                    Requires `portal.estates.view`. Blocks, price tiers, title, verification checks \
                    and plot counts by status.

                    `plotCounts.byStatus` only contains statuses that actually occur — an absent key \
                    means zero, and nothing fabricates a spread of zeros for a new estate.

                    A **404** here means the estate does not exist *or* is not yours: the row is \
                    removed by the database before this query runs, so the two are indistinguishable \
                    and an id cannot be probed for existence.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The estate"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<EstateDetailDto> getEstate(@PathVariable UUID id) {
        return ResponseEntity.ok(queryService.getEstate(id));
    }

    @Operation(summary = "List an estate's plots",
            description = """
                    Requires `portal.estates.view`.

                    Each plot carries `basePrice` (its tier's price), `cornerPremiumPct` (null \
                    unless the plot is a corner) and `price` (the result). All three are returned \
                    because showing only `price` on a corner plot would show a number matching no \
                    tier on the price list, with nothing to explain it.

                    `pricePerSqm` is null, never zero, when the plot has no nominal size — an \
                    apartment has no exclusive land area, so it has no rate.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of plots"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @GetMapping("/{id}/plots")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<PageResponse<PlotDetailDto>> listPlots(
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID blockId,
            @RequestParam(required = false) UUID priceTierId,
            @RequestParam(required = false) Boolean isCorner,
            @RequestParam(required = false) String propertyType,
            @RequestParam(required = false) String listingIntent,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {

        return ResponseEntity.ok(queryService.listPlots(
                id, status, blockId, priceTierId, isCorner, propertyType, listingIntent,
                pageable(cursor, limit, Sort.by(Sort.Direction.ASC, "plotNumber"))));
    }

    @Operation(summary = "One plot",
            description = """
                    Requires `portal.estates.view`. Same shape as the list.

                    `nominalSizeSqm` is what the plot is **sold as**, from its tier. \
                    `actualAreaSqm` is what the **survey** says, computed from the boundary, and is \
                    **null when the plot has no boundary** — it never falls back to the nominal \
                    figure, which would manufacture a survey result nobody produced.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plot"),
            @ApiResponse(responseCode = "404", description = "No such estate or plot", content = @Content())
    })
    @GetMapping("/{id}/plots/{plotId}")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<PlotDetailDto> getPlot(@PathVariable UUID id, @PathVariable UUID plotId) {
        return ResponseEntity.ok(queryService.getPlot(id, plotId));
    }

    /**
     * The estate and its plots as a GeoJSON {@code FeatureCollection},
     * deliberately unpaginated — a map draws the whole estate or it draws
     * nothing useful, and a half-rendered boundary would be worse than an
     * honest error.
     */
    @Operation(summary = "An estate's boundary and plots as GeoJSON",
            description = """
                    Requires `portal.estates.view`. A `FeatureCollection` in \
                    **`[longitude, latitude]`** order, SRID 4326 — the estate first, then each plot \
                    that has a boundary. Plots without one are omitted rather than returned with a \
                    null geometry.

                    Coordinates come back exactly as they were submitted.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A GeoJSON FeatureCollection"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @GetMapping("/{id}/geojson")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<GeoJsonFeatureCollectionDto> getGeoJson(@PathVariable UUID id) {
        return ResponseEntity.ok(queryService.getGeoJson(id));
    }

    private static Pageable pageable(String cursor, Integer limit, Sort sort) {
        int size = limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Pageable base = PageResponses.pageable(cursor, size);
        return PageRequest.of(base.getPageNumber(), base.getPageSize(), sort);
    }

    // --- writes ---

    @Operation(summary = "Create an estate",
            description = """
                    Requires `portal.estates.manage`.

                    **`footprint` is a GeoJSON `Polygon` in `[longitude, latitude]` order** — *not* \
                    Leaflet's `[latitude, longitude]`. This is the single most important line in \
                    this document: transposing them raises **no error**, it just places the estate \
                    somewhere else entirely. The ring must be closed (first position repeated last) \
                    and have at least four positions.

                    Coordinates are checked against Nigeria's bounding box, which catches many \
                    transpositions but **cannot catch all of them** — the country's longitude and \
                    latitude ranges overlap, so a transposed Abuja coordinate still lands inside \
                    Nigeria.

                    **Never send `tenantId`**: it comes from your token, and a value in the body is \
                    ignored. `branchId` is required only when your role is organization-wide.

                    `state` is required. The estate is created **unpublished**; publishing is a \
                    separate, deliberate action.

                    Creating an estate with a boundary immediately checks it against every other \
                    estate on the platform for overlaps.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The created estate"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the boundary is not a usable polygon", content = @Content()),
            @ApiResponse(responseCode = "409", description = "An estate with a matching name already exists for your company", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateDto> createEstate(@Valid @RequestBody CreateEstateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEstate(request));
    }

    /** PB-1..PB-3. Refused with the specific failing condition(s) named. */
    @Operation(summary = "Publish an estate to the marketplace",
            description = """
                    Requires `portal.estates.manage`. A deliberate action, never a side effect of \
                    creating or editing an estate.

                    **Refused unless all five conditions hold**, and the refusal names every one \
                    that failed: your company is verified; it holds the `marketplacePublishing` \
                    entitlement; its status is active; and no conflict blocks the estate.

                    A **`medium`** conflict does not refuse — it comes back as \
                    `warningConflictCount` on success.

                    A conflict refusal never identifies the other company.

                    Publishing an already-published estate re-checks everything. That is how you \
                    find out why a published estate is not appearing publicly.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Published, possibly with a warning about your own overlapping boundaries"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Refused; `code` names the first failing condition", content = @Content())
    })
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PublicationDto> publish(@PathVariable UUID id) {
        return ResponseEntity.ok(service.publish(id));
    }

    /** PB-4. Pulls one listing; estate, plots and tenant status untouched. */
    @Operation(summary = "Take an estate off the marketplace",
            description = """
                    Requires `portal.estates.manage`. Pulls one listing without involving the \
                    platform and without touching your company's status. The estate and its plots \
                    are untouched; only the `published` flag changes.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unpublished"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PublicationDto> unpublish(@PathVariable UUID id) {
        return ResponseEntity.ok(service.unpublish(id));
    }

    @Operation(summary = "Add a block to an estate",
            description = """
                    Requires `portal.estates.manage`. A subdivision — "Block C" — so plots can be \
                    addressed as "Block C, Plot 4". Deliberately thin: pricing and status belong to \
                    the plot or its tier, not here.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The created block"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "A block with that name already exists on this estate", content = @Content())
    })
    @PostMapping("/{id}/blocks")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<BlockDto> createBlock(
            @PathVariable UUID id, @Valid @RequestBody CreateBlockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBlock(id, request));
    }

    @Operation(summary = "Add a price tier",
            description = """
                    Requires `portal.estates.manage`. A size band with the developer's own price \
                    for it.

                    **The price is never derived from a per-square-metre rate.** Larger plots are \
                    routinely discounted per square metre, so pricing off a rate would quietly \
                    overcharge every large plot. A per-sqm figure is a display comparison only.

                    A `LAND_SIZE` tier needs a `sizeSqm`. A `UNIT_TYPE` tier (a built product, \
                    "3-bedroom terrace") does not — square metres are not its discriminator, and \
                    `label` carries the meaning.

                    Corner plots are **not** a tier: a corner is a per-plot modifier on its tier's \
                    price, set once per estate as `cornerPremiumPct`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The created tier"),
            @ApiResponse(responseCode = "400", description = "A LAND_SIZE tier with no size", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "A tier for that size already exists on this estate", content = @Content())
    })
    @PostMapping("/{id}/price-tiers")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PriceTierDto> createPriceTier(
            @PathVariable UUID id, @Valid @RequestBody CreatePriceTierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPriceTier(id, request));
    }

    /** A batch: a real estate has hundreds of plots and one request each would be unusable. */
    @Operation(summary = "Add plots (batch)",
            description = """
                    Requires `portal.estates.manage`. A batch, because a real estate has hundreds \
                    of plots and one request each would be unusable. Maximum 500.

                    **Do not send a plot's size**: `nominalSizeSqm` is copied from its tier, so the \
                    invoice and the deed cannot disagree. The one exception is \
                    `nominalSizeSqmOverride` on a `UNIT_TYPE` tier, which has no size of its own.

                    Each plot's boundary is optional, and must sit **inside** the estate's when both \
                    exist. `actualAreaSqm` is computed from that boundary in square metres.

                    Plot numbers are unique per block, and per estate for plots with no block.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The created plots"),
            @ApiResponse(responseCode = "400", description = "A boundary outside the estate, or a number repeated within the batch", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, tier or block", content = @Content()),
            @ApiResponse(responseCode = "409", description = "That plot number already exists in this block", content = @Content())
    })
    @PostMapping("/{id}/plots")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<List<PlotDto>> createPlots(
            @PathVariable UUID id, @Valid @RequestBody CreatePlotsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlots(id, request));
    }

    @Operation(summary = "Record the estate's title",
            description = """
                    Requires `portal.estates.manage`. The land title instrument — C of O, R of O, \
                    Governor's Consent, Gazette.

                    **One per estate**, and per *estate*, not per company: a developer can hold \
                    clean title on one estate and none on the next. Corporate documents (CAC, TIN) \
                    live on the company instead.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded title"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "This estate already has a title recorded", content = @Content())
    })
    @PostMapping("/{id}/title")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateTitleDto> recordTitle(
            @PathVariable UUID id, @Valid @RequestBody CreateEstateTitleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordTitle(id, request));
    }

    @Operation(summary = "Record a due-diligence check",
            description = """
                    Requires `portal.estates.manage`. AGIS registration, encroachment status or \
                    title verification.

                    **`not_checked` is the default and must never render as positive** — the absence \
                    of a check is not a clean bill of health.

                    A `verified` check **requires a `verificationSource`**: a green badge from a \
                    registry lookup and one from a person reading a PDF are different claims, and \
                    collapsing them would make the badge meaningless to a buyer.

                    Recording the same check type again updates it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded check"),
            @ApiResponse(responseCode = "400", description = "A verified check with no source", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @PostMapping("/{id}/verification-checks")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<VerificationCheckDto> recordVerificationCheck(
            @PathVariable UUID id, @Valid @RequestBody CreateVerificationCheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordVerificationCheck(id, request));
    }
}
