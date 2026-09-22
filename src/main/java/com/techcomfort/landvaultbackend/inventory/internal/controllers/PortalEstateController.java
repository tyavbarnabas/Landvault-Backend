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

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<EstateDetailDto> getEstate(@PathVariable UUID id) {
        return ResponseEntity.ok(queryService.getEstate(id));
    }

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

    @PostMapping
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateDto> createEstate(@Valid @RequestBody CreateEstateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEstate(request));
    }

    /** PB-1..PB-3. Refused with the specific failing condition(s) named. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PublicationDto> publish(@PathVariable UUID id) {
        return ResponseEntity.ok(service.publish(id));
    }

    /** PB-4. Pulls one listing; estate, plots and tenant status untouched. */
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PublicationDto> unpublish(@PathVariable UUID id) {
        return ResponseEntity.ok(service.unpublish(id));
    }

    @PostMapping("/{id}/blocks")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<BlockDto> createBlock(
            @PathVariable UUID id, @Valid @RequestBody CreateBlockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBlock(id, request));
    }

    @PostMapping("/{id}/price-tiers")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<PriceTierDto> createPriceTier(
            @PathVariable UUID id, @Valid @RequestBody CreatePriceTierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPriceTier(id, request));
    }

    /** A batch: a real estate has hundreds of plots and one request each would be unusable. */
    @PostMapping("/{id}/plots")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<List<PlotDto>> createPlots(
            @PathVariable UUID id, @Valid @RequestBody CreatePlotsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlots(id, request));
    }

    @PostMapping("/{id}/title")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateTitleDto> recordTitle(
            @PathVariable UUID id, @Valid @RequestBody CreateEstateTitleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordTitle(id, request));
    }

    @PostMapping("/{id}/verification-checks")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<VerificationCheckDto> recordVerificationCheck(
            @PathVariable UUID id, @Valid @RequestBody CreateVerificationCheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordVerificationCheck(id, request));
    }
}
