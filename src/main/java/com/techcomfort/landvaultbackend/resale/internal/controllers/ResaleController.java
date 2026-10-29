package com.techcomfort.landvaultbackend.resale.internal.controllers;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.resale.dto.ResaleEligibilityDto;
import com.techcomfort.landvaultbackend.resale.internal.service.ResaleEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Sell my plot. Phase 1: read only. See AGENTS.md, "Sell my plot". */
@RestController
@RequestMapping("/api/resale")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_RESALE)
public class ResaleController {

    private final ResaleEligibilityService eligibility;

    @Operation(summary = "Can I sell this plot, and what would I get",
            description = """
                    Requires `client.portfolio.view`. By owned-plot id or transaction id, the caller's own only. \
                    Read only: nothing is listed.

                    `kind` is `paid_in_full` (any purchase finance has verified in full), `installment` or \
                    `flexible` (a running plan). `canList` is false with every `reasons` entry at once — arrears, a \
                    notice running, a payment waiting for finance, an open restructure, past the date to finish by, \
                    KYC not approved, the estate off the marketplace.

                    **On a plan, a profit needs both thresholds** (`thresholds`): 30% of the agreed price verified \
                    and 6 months since the first verified payment. Before them it is an **early sale** \
                    (`earlySale: true`): the price is the developer's current price, the seller gets back what \
                    they paid and the developer gets the rest — offered only while that price is at or above the \
                    agreed price. `advice` says it in a sentence.

                    `price` quotes the split at a price: whole kobo, above zero, not below the agreed price on a \
                    plan; on an early sale it can't be set (`INVALID_PRICE`). Left out, the quote is at the \
                    developer's price when there is one.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Where the plot stands for a sale."),
            @ApiResponse(responseCode = "400", description = "`INVALID_PRICE` or `INVALID_PARAMETER`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`OWNED_PLOT_NOT_FOUND` — not one of yours.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/plots/{id}")
    @PreAuthorize("hasAuthority('client.portfolio.view')")
    public ResponseEntity<ResaleEligibilityDto> eligibility(@PathVariable UUID id,
                                                            @RequestParam(required = false) BigDecimal price) {
        return ResponseEntity.ok(eligibility.eligibility(currentUserId(), id, price));
    }

    private static UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
