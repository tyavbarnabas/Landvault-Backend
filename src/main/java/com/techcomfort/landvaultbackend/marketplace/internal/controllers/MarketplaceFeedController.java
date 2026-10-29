package com.techcomfort.landvaultbackend.marketplace.internal.controllers;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceFeedItemDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceResaleListingDto;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewSpecifications;
import com.techcomfort.landvaultbackend.marketplace.internal.service.MarketplaceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sell my plot, phase 3: the one public feed, estates and resale plots
 * together, and one resale plot. Public and GET-only like the estate routes
 * (listed one by one in {@code SecurityConfig}), rate limited by
 * {@code MarketplaceRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/marketplace")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_MARKETPLACE)
public class MarketplaceFeedController {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;

    private final MarketplaceQueryService service;

    @Operation(
            summary = "Browse estates and resale plots together (no authentication)",
            description = """
                    **One marketplace**: developers' estates (`listingType: primary`, `data` an estate, the \
                    same shape as `/estates`) and plots buyers are selling (`listingType: resale`, `data` \
                    one plot at a fixed price), sorted and paged as one list.

                    Filters apply to both: `state`, `city`, `q`, price (within one `currency`), size \
                    (a resale plot's size is its own), `titleType` (verified titles only; a resale \
                    plot's title is its estate's) and `intent` (the estate's). `listingType` \
                    (`primary` | `resale` | `all`, default `all`) narrows the kind; `estateId` lists \
                    one estate and its resales — `listingType=resale&estateId=…` for an estate page.

                    `sort`: `newest` (default — when published or listed), `price_low` (an estate's \
                    from-price, a resale's price), `price_per_sqm`, `plots_remaining` (a resale plot \
                    counts as one).

                    A resale plot never names its seller or says what they paid. Its price is fixed: \
                    there are no offers.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of estates and resale plots"),
            @ApiResponse(responseCode = "400", description = "Unknown `sort`, `listingType`, `titleType` or "
                    + "`intent`", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited; see `Retry-After`",
                    content = @Content())
    })
    @SecurityRequirements
    @GetMapping("/listings")
    public ResponseEntity<PageResponse<MarketplaceFeedItemDto>> listings(
            @Parameter(description = "`primary`, `resale` or `all` (default)")
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) UUID estateId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Currency currency,
            @RequestParam(required = false) BigDecimal minSize,
            @RequestParam(required = false) BigDecimal maxSize,
            @Parameter(description = "Title type, e.g. `C of O`. Matches titles HomeTrue has verified only.")
            @RequestParam(required = false) String titleType,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {

        ListingViewSpecifications.Filters filters = new ListingViewSpecifications.Filters(
                q, state, city, minPrice, maxPrice, currency == null ? Currency.NGN : currency,
                minSize, maxSize,
                titleType == null || titleType.isBlank() ? null : TitleType.fromValue(titleType),
                intent == null || intent.isBlank() ? null : EstateIntent.fromValue(intent));
        return ResponseEntity.ok(service.feed(filters, listingType(listingType), estateId,
                pageable(cursor, limit, sort)));
    }

    @Operation(
            summary = "One plot for resale (no authentication)",
            description = """
                    The plot, its estate, the developer, the fixed price and whether a transfer needs \
                    the developer's consent. `plotId` is the plot on the estate's map \
                    (`/estates/{estateId}/geojson`), where it shows as unavailable — it isn't for sale \
                    from the developer. Never the seller or what they paid.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plot"),
            @ApiResponse(responseCode = "404", description = "No such listing, or it isn't on the "
                    + "marketplace now (taken down, expired, or its estate unlisted) — one answer",
                    content = @Content())
    })
    @SecurityRequirements
    @GetMapping("/resales/{id}")
    public ResponseEntity<MarketplaceResaleListingDto> resale(@PathVariable UUID id) {
        return ResponseEntity.ok(service.resale(id));
    }

    @ExceptionHandler(MarketplaceQueryService.ListingNotFound.class)
    public ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Listing not found.", "LISTING_NOT_FOUND"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badValue(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "VALIDATION_ERROR"));
    }

    /** The view's value, or null for both. */
    private static String listingType(String value) {
        return switch (value == null || value.isBlank() ? "all" : value) {
            case "all" -> null;
            case "primary" -> "PRIMARY";
            case "resale" -> "RESALE";
            default -> throw new IllegalArgumentException(
                    "Unknown listingType '" + value + "'. Use primary, resale or all.");
        };
    }

    /** Listing id as the last key so equal sort values still page deterministically. */
    private static Pageable pageable(String cursor, Integer limit, String sort) {
        int size = limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Sort order = switch (sort == null ? "newest" : sort) {
            case "price_low" -> Sort.by(Sort.Order.asc("price").nullsLast());
            case "price_per_sqm" -> Sort.by(Sort.Order.asc("pricePerSqm").nullsLast());
            case "plots_remaining" -> Sort.by(Sort.Order.desc("plotsRemaining"));
            case "newest" -> Sort.by(Sort.Order.desc("listedAt").nullsLast());
            default -> throw new IllegalArgumentException(
                    "Unknown sort '" + sort + "'. Use newest, price_low, price_per_sqm or plots_remaining.");
        };
        Pageable base = PageResponses.pageable(cursor, size);
        return PageRequest.of(base.getPageNumber(), base.getPageSize(), order.and(Sort.by("listingId")));
    }
}
