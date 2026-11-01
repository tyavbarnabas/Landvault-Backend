package com.techcomfort.landvaultbackend.resale.internal.controllers;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.resale.dto.HoldResaleRequest;
import com.techcomfort.landvaultbackend.resale.dto.ResaleHoldDto;
import com.techcomfort.landvaultbackend.resale.internal.service.ResaleHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sell my plot, phase 5a: a buyer holds a resale plot to buy it. See AGENTS.md, "Sell my plot". */
@RestController
@RequestMapping("/api/resale/holds")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_RESALE)
public class ResaleHoldController {

    private final ResaleHoldService holds;

    @Operation(summary = "Hold a resale plot to buy it",
            description = """
                    Requires `client.checkout.reserve` and approved identity verification (403 `KYC_REQUIRED`). \
                    Holds the plot for 45 minutes, like any reservation: it leaves the marketplace, and the price \
                    is fixed for this hold. Proceed to buy with the returned `reservationId`; \
                    `DELETE /api/reservations/{reservationId}` lets it go. Only paying in full is offered.

                    One answer, 409 `LISTING_NOT_AVAILABLE`, whether it is held by someone else, has come down \
                    or is no longer for sale. 409 `CANNOT_BUY_OWN_PLOT` for the seller — under this account or \
                    another verified as the same person.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Held."),
            @ApiResponse(responseCode = "403", description = "`KYC_REQUIRED`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "`LISTING_NOT_AVAILABLE`, `CANNOT_BUY_OWN_PLOT`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<ResaleHoldDto> hold(@Valid @RequestBody HoldResaleRequest request) {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return ResponseEntity.status(HttpStatus.CREATED).body(holds.hold(scope.userId(), request.listingId()));
    }
}
