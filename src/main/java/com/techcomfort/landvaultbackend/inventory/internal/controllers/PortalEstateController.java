package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.internal.service.PortalEstateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A tenant's own estate inventory. Creation only — reads are slice 3,
 * conflict detection slice 4.
 * <p>
 * Every route requires {@code portal.estates.manage}, the first
 * {@code portal.*} permission in this codebase. The tenant an estate belongs
 * to comes from the authenticated caller's scope, never from a request body;
 * see {@code PortalEstateService}.
 */
@RestController
@RequestMapping("/api/portal/estates")
@RequiredArgsConstructor
public class PortalEstateController {

    private final PortalEstateService service;

    @PostMapping
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateDto> createEstate(@Valid @RequestBody CreateEstateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEstate(request));
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
