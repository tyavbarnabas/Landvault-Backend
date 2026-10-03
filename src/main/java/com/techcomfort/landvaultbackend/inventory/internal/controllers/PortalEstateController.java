package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.inventory.dto.AddEstateBoundaryRequest;
import com.techcomfort.landvaultbackend.inventory.dto.UpdateEstateRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.techcomfort.landvaultbackend.inventory.dto.PlotStatusChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.ChangePlotStatusRequest;
import com.techcomfort.landvaultbackend.inventory.dto.BulkPlotStatusRequest;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import com.techcomfort.landvaultbackend.inventory.internal.service.PlotImportService;
import com.techcomfort.landvaultbackend.inventory.dto.PlotImportReportDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotTierChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotBoundaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.MovePlotTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CorrectPlotBoundaryRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateBoundaryDto;
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
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierImpactDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierUpdateDto;
import com.techcomfort.landvaultbackend.inventory.dto.PublicationDto;
import com.techcomfort.landvaultbackend.inventory.dto.UpdateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.UpdatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.internal.service.InventoryEditService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A tenant's own estate inventory — creation, reads and edits. Conflict detection
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
    private final InventoryEditService editService;
    private final PlotImportService importService;

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

    @Operation(summary = "Update an estate's details",
            description = """
                    Requires `portal.estates.manage`. Name, description, area, city, state, address, \
                    corner premium, intent and amenities. **Every field is optional — left out means \
                    unchanged**; a blank text field clears it (except `name` and `state`, which can't be \
                    cleared). `amenities` replaces the whole list.

                    **A corner-premium change reprices every corner plot at once**, including on the \
                    live marketplace. Buyers who already reserved keep the price they agreed to.

                    Refused, never silently ignored: `footprint` (use `POST .../boundary`, which runs the \
                    overlap check), `published` (use publish/unpublish), and a different `branchId`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The estate as it now stands"),
            @ApiResponse(responseCode = "400", description = "A refused field, a blank name or state, or an invalid value", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Another of your estates already has a matching name", content = @Content())
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateDto> updateEstate(@PathVariable UUID id, @Valid @RequestBody UpdateEstateRequest request) {
        return ResponseEntity.ok(service.updateEstate(id, request));
    }

    @Operation(summary = "Add a boundary to an estate that has none",
            description = """
                    Requires `portal.estates.manage`. For an estate created before its survey was \
                    ready. **An estate cannot be published without a boundary**, so this is how one \
                    becomes listable.

                    GeoJSON `Polygon`, coordinates in **`[longitude, latitude]`** order — the same \
                    rules as creating an estate.

                    Only when the estate has **no** boundary yet; changing an existing one is not \
                    supported (409 `BOUNDARY_ALREADY_SET`). Every plot that already has a boundary \
                    must sit inside the new one, or the request is refused naming the plots that \
                    don't, and nothing is saved.

                    **Checked immediately for overlaps** with every other estate on the platform; \
                    the response says whether that blocks publication. An estate that was already \
                    published returns to the marketplace on its own if it now passes every \
                    condition.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Boundary added; overlap result included"),
            @ApiResponse(responseCode = "400", description = "Not a usable polygon (`INVALID_GEOMETRY`), "
                    + "or existing plots fall outside it (`PLOT_OUTSIDE_ESTATE`)", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`BOUNDARY_ALREADY_SET`", content = @Content())
    })
    @PostMapping("/{id}/boundary")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateBoundaryDto> addBoundary(
            @PathVariable UUID id, @Valid @RequestBody AddEstateBoundaryRequest request) {
        return ResponseEntity.ok(service.addBoundary(id, request));
    }

    /** PB-1..PB-3. Refused with the specific failing condition(s) named. */
    @Operation(summary = "Publish an estate to the marketplace",
            description = """
                    Requires `portal.estates.manage`. A deliberate action, never a side effect of \
                    creating or editing an estate.

                    **Refused unless every condition holds**, and the refusal names every one \
                    that failed: your company is verified; it holds the `marketplacePublishing` \
                    entitlement; its status is active; a fee schedule and refund terms are \
                    declared; the estate has a boundary; and no conflict blocks it. \
                    `GET /api/portal/estates/{id}` shows each condition without attempting a publish.

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

    @Operation(summary = "Rename a block",
            description = """
                    Requires `portal.estates.manage`. Changes a block's `name` and/or `label`; a \
                    field left out is unchanged. Nothing depends on the name beyond display, so \
                    this is a low-risk edit — but names stay unique within the estate.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The block as it now stands"),
            @ApiResponse(responseCode = "400", description = "A blank name", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such block on it", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Another block on this estate already has that name", content = @Content())
    })
    @PutMapping("/{id}/blocks/{blockId}")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<BlockDto> updateBlock(
            @PathVariable UUID id, @PathVariable UUID blockId, @Valid @RequestBody UpdateBlockRequest request) {
        return ResponseEntity.ok(editService.updateBlock(id, blockId, request));
    }

    @Operation(summary = "Correct a plot's boundary",
            description = """
                    Requires `portal.estates.manage`. Replaces the boundary of an **available** plot \
                    — for example one entered with two coordinates transposed. Reserved and sold \
                    plots are refused (`PLOT_NOT_EDITABLE`): their boundary is what a buyer agreed to.

                    GeoJSON `Polygon`, coordinates in **`[longitude, latitude]`** order. Must sit \
                    inside the estate's boundary when the estate has one. A boundary can be \
                    replaced but not removed.

                    The surveyed area is **recomputed** from the new boundary, and plot overlap \
                    detection re-runs for the estate: an overlap the correction removes resolves \
                    on its own, and one it creates is recorded. The response gives the count of \
                    overlapping plot pairs before and after.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Corrected; area and overlap counts included"),
            @ApiResponse(responseCode = "400", description = "Not a usable polygon (`INVALID_GEOMETRY`), "
                    + "or outside the estate (`PLOT_OUTSIDE_ESTATE`)", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such plot on it", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLOT_NOT_EDITABLE`: reserved or sold", content = @Content())
    })
    @PutMapping("/{id}/plots/{plotId}/boundary")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PlotBoundaryDto> correctPlotBoundary(
            @PathVariable UUID id, @PathVariable UUID plotId, @Valid @RequestBody CorrectPlotBoundaryRequest request) {
        return ResponseEntity.ok(editService.correctPlotBoundary(id, plotId, request));
    }

    @Operation(summary = "Move a plot to a different price tier",
            description = """
                    Requires `portal.estates.manage`. For an **available** plot that was put in the \
                    wrong tier. Changes its **price** and, for a land-size tier, its **nominal size** \
                    — the size on the deed. Reserved and sold plots are refused (`PLOT_NOT_EDITABLE`); \
                    a buyer's agreed price and size never change.

                    The target tier must be on the same estate and in the **same currency** \
                    (`TIER_CURRENCY_MISMATCH` otherwise).

                    - Moving to a **land-size** tier: the plot takes that tier's size. \
                    `nominalSizeSqmOverride` is refused.
                    - Moving to a **unit-type** tier: the plot keeps its current size, or takes \
                    `nominalSizeSqmOverride` if given.

                    The response shows price and size before and after.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Moved; before and after included"),
            @ApiResponse(responseCode = "400", description = "Different currency, or an override with a land-size tier", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, plot or tier on it", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLOT_NOT_EDITABLE`: reserved or sold", content = @Content())
    })
    @PutMapping("/{id}/plots/{plotId}/tier")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PlotTierChangeDto> movePlotToTier(
            @PathVariable UUID id, @PathVariable UUID plotId, @Valid @RequestBody MovePlotTierRequest request) {
        return ResponseEntity.ok(editService.movePlotToTier(id, plotId, request));
    }

    @Operation(summary = "Download a plot-file template for this estate",
            description = """
                    Requires `portal.estates.view`. A GeoJSON file to edit rather than documentation \
                    to read: two example plots **inside this estate's boundary**, this estate's real \
                    tier labels, and the properties the import reads (`plot_number`, `block`, `tier`, \
                    `corner`). Coordinates are `[longitude, latitude]` in WGS 84 — **not UTM \
                    metres**, which is what Nigerian survey software usually produces.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A `.geojson` file"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @GetMapping(value = "/{id}/plots/import/template", produces = "application/geo+json")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<String> plotImportTemplate(@PathVariable UUID id) {
        PlotImportService.TemplateFile template = importService.template(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.fileName()).build().toString())
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(template.content());
    }

    @Operation(summary = "Preview a plot file — find every problem, write nothing",
            description = """
                    Requires `portal.estates.manage`. Upload a GeoJSON `FeatureCollection`, one \
                    `Feature` per plot (at most 500). Returns **one report** for the whole file: \
                    errors that would stop the import, warnings that wouldn't (plots overlapping \
                    each other), the blocks that would be created and how many plots each tier gets.

                    A preview is **not a promise**: the import checks everything again.

                    Which property carries what is configurable; the defaults match the template. \
                    `tier` matches a tier's label, or a land tier's size in sqm. Every imported \
                    plot gets `status` (`available-dev` or `available-inv`).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The report; `canImport` says whether the import would proceed"),
            @ApiResponse(responseCode = "400", description = "An invalid `status`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @PostMapping(value = "/{id}/plots/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PlotImportReportDto> previewPlotImport(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "plot_number") String plotNumberProperty,
            @RequestParam(defaultValue = "block") String blockProperty,
            @RequestParam(defaultValue = "tier") String tierProperty,
            @RequestParam(defaultValue = "corner") String cornerProperty,
            @RequestParam(defaultValue = "available-dev") String status) throws IOException {
        return ResponseEntity.ok(importService.preview(id, file.getBytes(), new PlotImportService.Options(
                plotNumberProperty, blockProperty, tierProperty, cornerProperty, status)));
    }

    @Operation(summary = "Import plots from a file — all or nothing",
            description = """
                    Requires `portal.estates.manage`. Same file and options as the preview. \
                    **Everything is checked again**; if the file has any error, nothing is created \
                    and the report comes back with HTTP 422. Otherwise every plot is created in one \
                    go, missing blocks are created, and overlap detection runs once for the estate \
                    — overlaps are recorded for review, never a reason to refuse.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Imported; the report includes `createdCount`"),
            @ApiResponse(responseCode = "400", description = "An invalid `status`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content()),
            @ApiResponse(responseCode = "422", description = "The file has errors; nothing was created. Body is the report.")
    })
    @PostMapping(value = "/{id}/plots/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PlotImportReportDto> importPlots(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "plot_number") String plotNumberProperty,
            @RequestParam(defaultValue = "block") String blockProperty,
            @RequestParam(defaultValue = "tier") String tierProperty,
            @RequestParam(defaultValue = "corner") String cornerProperty,
            @RequestParam(defaultValue = "available-dev") String status) throws IOException {
        return ResponseEntity.ok(importService.importPlots(id, file.getBytes(), file.getOriginalFilename(),
                new PlotImportService.Options(plotNumberProperty, blockProperty, tierProperty, cornerProperty, status)));
    }

    @Operation(summary = "Withhold a plot, or return it to the market",
            description = """
                    Requires `portal.estates.manage`. Takes an **available** plot off the market \
                    (`withheld`) — a survey dispute, a staff allocation — or puts a withheld one back.

                    - `withheld` — from `available-dev` or `available-inv`.
                    - `available` — back to **the variant it had** before being withheld. Development \
                    and investment plots are never flattened into one.
                    - `available-dev` / `available-inv` — set the variant explicitly.

                    **Never `reserved` or `sold`** — only checkout reaches those. A reserved or sold \
                    plot is refused (`PLOT_NOT_EDITABLE`). On the public marketplace a withheld plot \
                    shows as unavailable. Returning a land plot to the market takes its tier's current \
                    size.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed, or already in that status (nothing recorded)"),
            @ApiResponse(responseCode = "400", description = "Not one of the allowed statuses", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such plot on it", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLOT_NOT_EDITABLE`: reserved or sold", content = @Content())
    })
    @PutMapping("/{id}/plots/{plotId}/status")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PlotStatusChangeDto> changePlotStatus(
            @PathVariable UUID id, @PathVariable UUID plotId, @Valid @RequestBody ChangePlotStatusRequest request) {
        return ResponseEntity.ok(editService.changePlotStatus(id, plotId, request));
    }

    @Operation(summary = "Change many plots' status at once",
            description = """
                    Requires `portal.estates.manage`. Same statuses as the single-plot route, for up to \
                    500 plots — a phase launch in one action.

                    **Skip and report, never all or nothing**: one reserved plot doesn't block a \
                    200-plot launch; it comes back in `skipped` with the reason. Each plot is \
                    checked **at the moment it is changed**, so a buyer reserving a plot at the same \
                    instant keeps their hold. `dryRun: true` reports what would change without \
                    changing anything — but the real request re-checks every plot regardless.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What changed and what was skipped, with reasons"),
            @ApiResponse(responseCode = "400", description = "Not one of the allowed statuses, or more than 500 plots", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or not yours", content = @Content())
    })
    @PostMapping("/{id}/plots/status")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PlotStatusChangeDto> changePlotStatuses(
            @PathVariable UUID id, @Valid @RequestBody BulkPlotStatusRequest request) {
        return ResponseEntity.ok(editService.changePlotStatuses(id, request));
    }

    @Operation(summary = "Withdraw a plot that was never sold, reserved or disputed",
            description = """
                    Requires `portal.estates.manage`. Removes a plot entered by mistake. **Only a plot \
                    with no history**: never reserved or bought (in any state, including an expired \
                    hold) and never part of a boundary conflict. Anything else is refused \
                    (`PLOT_HAS_HISTORY`) — withhold it instead. The plot number can then be reused.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Withdrawn"),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such plot on it", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLOT_NOT_EDITABLE` (reserved/sold) or `PLOT_HAS_HISTORY`", content = @Content())
    })
    @DeleteMapping("/{id}/plots/{plotId}")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<Void> withdrawPlot(@PathVariable UUID id, @PathVariable UUID plotId) {
        editService.withdrawPlot(id, plotId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Retire a tier — no new plots may join it",
            description = """
                    Requires `portal.estates.manage`. Stops new plots being created on, imported into or \
                    moved to the tier. **Existing plots keep it**, and available ones stay on sale at \
                    its price — to take them off the market, withhold them. Undo with `reinstate`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tier, with `retiredAt` set"),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such tier on it", content = @Content())
    })
    @PostMapping("/{id}/price-tiers/{tierId}/retire")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PriceTierDto> retireTier(@PathVariable UUID id, @PathVariable UUID tierId) {
        return ResponseEntity.ok(editService.retireTier(id, tierId, true));
    }

    @Operation(summary = "Reinstate a retired tier", description = "Requires `portal.estates.manage`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tier, with `retiredAt` cleared"),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such tier on it", content = @Content())
    })
    @PostMapping("/{id}/price-tiers/{tierId}/reinstate")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PriceTierDto> reinstateTier(@PathVariable UUID id, @PathVariable UUID tierId) {
        return ResponseEntity.ok(editService.retireTier(id, tierId, false));
    }

    @Operation(summary = "Change a tier's price, label or size",
            description = """
                    Requires `portal.estates.manage`. Fields left out are unchanged.

                    **One edit reprices every plot on the tier** — that is what tiers are for. A \
                    buyer who has already reserved keeps the price captured when they took the \
                    hold; nothing here reaches into a reservation or transaction.

                    **A size change reaches available plots only.** Size is what appears on a \
                    deed, and a reservation locks price but not size, so reserved and sold plots \
                    keep theirs. The response says how many plots changed and how many kept the \
                    old size, by status. Call `GET .../impact` first to see the reach.

                    `tierType` and `currency` cannot change — a different value is a different \
                    tier. Sending the current value is accepted.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tier, and what a size change reached"),
            @ApiResponse(responseCode = "400", description = "`TIER_TYPE_IMMUTABLE`, `TIER_CURRENCY_IMMUTABLE`, "
                    + "or a size on a UNIT_TYPE tier", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such tier on it", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Another tier on this estate already has that size", content = @Content())
    })
    @PutMapping("/{id}/price-tiers/{tierId}")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PriceTierUpdateDto> updatePriceTier(
            @PathVariable UUID id, @PathVariable UUID tierId, @Valid @RequestBody UpdatePriceTierRequest request) {
        return ResponseEntity.ok(editService.updatePriceTier(id, tierId, request));
    }

    @Operation(summary = "Preview what a tier edit would reach",
            description = """
                    Requires `portal.estates.view`. How many plots point at this tier, by status — \
                    "this affects 120 plots, 8 of which are reserved". Statuses with no plots are \
                    absent rather than zero.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plot counts for the tier"),
            @ApiResponse(responseCode = "404", description = "No such estate, or no such tier on it", content = @Content())
    })
    @GetMapping("/{id}/price-tiers/{tierId}/impact")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<PriceTierImpactDto> priceTierImpact(@PathVariable UUID id, @PathVariable UUID tierId) {
        return ResponseEntity.ok(editService.priceTierImpact(id, tierId));
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
