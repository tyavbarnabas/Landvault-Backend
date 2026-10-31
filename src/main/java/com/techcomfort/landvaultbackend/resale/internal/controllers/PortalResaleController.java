package com.techcomfort.landvaultbackend.resale.internal.controllers;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.resale.dto.PortalResaleListingDto;
import com.techcomfort.landvaultbackend.resale.dto.RefuseConsentRequest;
import com.techcomfort.landvaultbackend.resale.internal.service.ResaleConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Sell my plot, phase 4 (SP-14): the developer's view of resales on its estates, and consent. */
@RestController
@RequestMapping("/api/portal/resale/listings")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_RESALE)
public class PortalResaleController {

    private final ResaleConsentService consent;

    @Operation(summary = "Resales on our estates",
            description = """
                    Requires `portal.resale.consent` and a company-wide view (403 \
                    `RESALE_CONSENT_REQUIRES_COMPANY_WIDE_SCOPE` when narrowed to a branch). Every plot buyers are \
                    reselling on the company's estates: those waiting for your consent first (soonest due first), \
                    then the rest, newest first. `status` narrows it (`awaiting_consent`, `listed`, `withdrawn`, \
                    `expired`, `refused`, `ended`). Shows the seller's name and purchase reference — they are your \
                    customer — never what they paid.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of listings."),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR` (unknown `status`).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`RESALE_CONSENT_REQUIRES_COMPANY_WIDE_SCOPE`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping
    @PreAuthorize("hasAuthority('portal.resale.consent')")
    public ResponseEntity<PageResponse<PortalResaleListingDto>> list(
            @Parameter(description = "One status, or leave out for all.") @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(consent.list(status, PageResponses.pageable(cursor, limit)));
    }

    @Operation(summary = "Give consent to a resale",
            description = """
                    Requires `portal.resale.consent`, company-wide. Only while the listing is `awaiting_consent` \
                    (409 `CONSENT_NOT_AWAITED` otherwise) and within its 14 days (409 `CONSENT_REQUEST_LAPSED`). \
                    The plot goes on the marketplace now, for 90 days. If something changed while it waited — the \
                    plan fell into arrears, notice was given, the estate left the marketplace — your consent is \
                    recorded but it doesn't go live: it comes back `ended` with the `closeReason`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consent recorded."),
            @ApiResponse(responseCode = "404", description = "`LISTING_NOT_FOUND` (not on your estates).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "`CONSENT_NOT_AWAITED`, `CONSENT_REQUEST_LAPSED`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/{id}/consent")
    @PreAuthorize("hasAuthority('portal.resale.consent')")
    public ResponseEntity<PortalResaleListingDto> give(@PathVariable UUID id) {
        return ResponseEntity.ok(consent.consent(id));
    }

    @Operation(summary = "Refuse consent to a resale",
            description = """
                    Requires `portal.resale.consent`, company-wide. A reason is required, and the seller reads it. \
                    The listing comes down as `refused`; no buyer ever saw it and nothing was paid. The same \
                    409s as giving consent.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refused."),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR` (no reason).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`LISTING_NOT_FOUND`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "`CONSENT_NOT_AWAITED`, `CONSENT_REQUEST_LAPSED`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/{id}/refuse")
    @PreAuthorize("hasAuthority('portal.resale.consent')")
    public ResponseEntity<PortalResaleListingDto> refuse(@PathVariable UUID id,
                                                         @Valid @RequestBody RefuseConsentRequest request) {
        return ResponseEntity.ok(consent.refuse(id, request.reason()));
    }
}
