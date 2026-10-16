package com.techcomfort.landvaultbackend.portfolio.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.portfolio.dto.OwnedPlotDto;
import com.techcomfort.landvaultbackend.portfolio.dto.PortfolioSummaryDto;
import com.techcomfort.landvaultbackend.portfolio.internal.service.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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

import java.util.UUID;

/** A buyer's portfolio. Read-only: ownership comes only from finance verifying a sale. See AGENTS.md, "Portfolio". */
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTFOLIO)
public class PortfolioController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PortfolioService service;

    @Operation(summary = "My plots",
            description = """
                    Requires `client.portfolio.view`. Every plot the caller owns, **across every company**, plus \
                    paid purchases finance hasn't verified yet (`pending_verification`, no equity yet). A hold \
                    that isn't paid is not here — it's a checkout, not ownership.

                    Newest first; `sort=urgency` puts arrears first, then anything due within a week, then \
                    in progress, then settled — applied before paging. Paged (`limit` default 20, max 100).

                    Includes transferred and superseded records (history): leave those out of any total — or \
                    use `/summary`, which already does.""")
    @GetMapping("/plots")
    @PreAuthorize("hasAuthority('client.portfolio.view')")
    public ResponseEntity<PageResponse<OwnedPlotDto>> list(@RequestParam(required = false) String sort,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(service.list(currentUserId(), sort, PageResponses.pageable(cursor,
                limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "One of my plots",
            description = "Requires `client.portfolio.view`. By owned-plot id or by transaction id (a pending "
                    + "purchase's id keeps working after finance verifies it). Someone else's is a 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`OWNED_PLOT_NOT_FOUND`", content = @Content())
    })
    @GetMapping("/plots/{id}")
    @PreAuthorize("hasAuthority('client.portfolio.view')")
    public ResponseEntity<OwnedPlotDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(currentUserId(), id));
    }

    @Operation(summary = "My totals, per currency",
            description = """
                    Requires `client.portfolio.view`. One entry per currency — never added across currencies. \
                    Only plots still owned count (transferred and superseded records are history), and \
                    purchases pending verification are counted separately, not in the totals.""")
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('client.portfolio.view')")
    public ResponseEntity<PortfolioSummaryDto> summary() {
        return ResponseEntity.ok(service.summary(currentUserId()));
    }

    private static UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
