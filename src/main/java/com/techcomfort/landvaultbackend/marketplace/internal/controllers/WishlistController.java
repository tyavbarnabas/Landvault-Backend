package com.techcomfort.landvaultbackend.marketplace.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.marketplace.dto.WishlistItemDto;
import com.techcomfort.landvaultbackend.marketplace.internal.service.WishlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A buyer's saved listings. Under {@code /api/marketplace/} but <strong>not
 * public</strong>: absent from {@code SecurityConfig}'s public lists, so it
 * needs a login. See AGENTS.md, "The buyer's wishlist".
 */
@RestController
@RequestMapping("/api/marketplace/wishlist")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_WISHLIST)
public class WishlistController {

    private final WishlistService service;

    @Operation(summary = "My wishlist",
            description = """
                    Requires `client.marketplace.view`. The caller's own saved listings, newest first; at most \
                    200, so a plain array. An item whose listing has left the marketplace stays, with \
                    `available: false`, its name when saved, and null current figures — it never says why.""")
    @GetMapping
    @PreAuthorize("hasAuthority('client.marketplace.view')")
    public ResponseEntity<List<WishlistItemDto>> list() {
        return ResponseEntity.ok(service.list(currentUserId()));
    }

    @Operation(summary = "Save a listing",
            description = """
                    Requires `client.marketplace.view`. No body. `listingType` is `primary` (an estate; \
                    `listingId` is its id); `resale` is refused until resale exists. Idempotent: 201 the first \
                    time, 200 when already saved, with `savedAt` and `priceAtSave` unchanged. `priceAtSave` is \
                    the listing's from-price now, worked out here — never taken from the request.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Saved"),
            @ApiResponse(responseCode = "200", description = "Already saved; unchanged"),
            @ApiResponse(responseCode = "400", description = "`WISHLIST_TYPE_UNKNOWN`, "
                    + "`WISHLIST_TYPE_NOT_SUPPORTED` or `INVALID_PARAMETER`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`LISTING_NOT_FOUND`: not on the marketplace now",
                    content = @Content()),
            @ApiResponse(responseCode = "409", description = "`WISHLIST_FULL`: 200 listings already saved",
                    content = @Content())
    })
    @PutMapping("/{listingType}/{listingId}")
    @PreAuthorize("hasAuthority('client.marketplace.view')")
    public ResponseEntity<WishlistItemDto> save(@PathVariable String listingType, @PathVariable UUID listingId) {
        WishlistService.Saved saved = service.save(currentUserId(), listingType, listingId);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.item());
    }

    @Operation(summary = "Remove a saved listing",
            description = "Requires `client.marketplace.view`. Idempotent: 204 whether or not it was saved.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Not saved any more"),
            @ApiResponse(responseCode = "400", description = "`WISHLIST_TYPE_UNKNOWN`, "
                    + "`WISHLIST_TYPE_NOT_SUPPORTED` or `INVALID_PARAMETER`", content = @Content())
    })
    @DeleteMapping("/{listingType}/{listingId}")
    @PreAuthorize("hasAuthority('client.marketplace.view')")
    public ResponseEntity<Void> remove(@PathVariable String listingType, @PathVariable UUID listingId) {
        service.remove(currentUserId(), listingType, listingId);
        return ResponseEntity.noContent().build();
    }

    private static UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
