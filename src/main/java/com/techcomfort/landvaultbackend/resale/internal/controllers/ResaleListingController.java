package com.techcomfort.landvaultbackend.resale.internal.controllers;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.resale.dto.CreateListingRequest;
import com.techcomfort.landvaultbackend.resale.dto.ResaleListingDto;
import com.techcomfort.landvaultbackend.resale.dto.UpdateListingRequest;
import com.techcomfort.landvaultbackend.resale.internal.service.ResaleListingService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Sell my plot, phase 2: the seller's listings. See AGENTS.md, "Sell my plot". */
@RestController
@RequestMapping("/api/resale/listings")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_RESALE)
public class ResaleListingController {

    private final ResaleListingService listings;

    @Operation(summary = "List my plot for sale",
            description = """
                    Requires `client.resale.manage`. Checks every condition of \
                    `GET /api/resale/plots/{id}` again, now: anything failing is 409 `RESALE_NOT_ALLOWED` naming every \
                    reason. On an early sale leave `price` out — its price is always the developer's current one; \
                    otherwise `price` is required (`INVALID_PRICE`). One live listing per plot (`ALREADY_LISTED`). A \
                    listing lasts 90 days; on a flexible plan the first listing stops the inactivity clock while it \
                    is up.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listed."),
            @ApiResponse(responseCode = "400", description = "`INVALID_PRICE`, `VALIDATION_ERROR`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`OWNED_PLOT_NOT_FOUND`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "`RESALE_NOT_ALLOWED`, `ALREADY_LISTED`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping
    @PreAuthorize("hasAuthority('client.resale.manage')")
    public ResponseEntity<ResaleListingDto> list(@Valid @RequestBody CreateListingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(listings.list(currentUserId(), request.plotId(),
                request.price()));
    }

    @Operation(summary = "My listings",
            description = "Requires `client.resale.manage`. Newest first, every status; a live one carries its split.")
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('client.resale.manage')")
    public ResponseEntity<List<ResaleListingDto>> mine() {
        return ResponseEntity.ok(listings.mine(currentUserId()));
    }

    @Operation(summary = "Change my asking price",
            description = "Requires `client.resale.manage`. A live listing that isn't an early sale; the same price "
                    + "rules as listing. 409 `LISTING_NOT_LIVE` once it has come down.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('client.resale.manage')")
    public ResponseEntity<ResaleListingDto> changePrice(@PathVariable UUID id,
                                                        @Valid @RequestBody UpdateListingRequest request) {
        return ResponseEntity.ok(listings.changePrice(currentUserId(), id, request.price()));
    }

    @Operation(summary = "Take my listing down",
            description = "Requires `client.resale.manage`. 409 `LISTING_NOT_LIVE` if it has already come down.")
    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('client.resale.manage')")
    public ResponseEntity<ResaleListingDto> withdraw(@PathVariable UUID id) {
        return ResponseEntity.ok(listings.withdraw(currentUserId(), id));
    }

    private static UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
