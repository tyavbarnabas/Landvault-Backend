package com.techcomfort.landvaultbackend.marketplace.internal.controllers;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewSpecifications;
import com.techcomfort.landvaultbackend.marketplace.internal.service.MarketplaceQueryService;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The public marketplace. <strong>No authentication</strong>: these are the
 * first routes in the system an anonymous caller can reach, each listed
 * explicitly (and GET-only) in {@code SecurityConfig}, never under a
 * {@code /api/marketplace/**} wildcard, because wishlist, enquiries and
 * reservations will live under that prefix and must not be public.
 * Rate-limited by {@code MarketplaceRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/marketplace/estates")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_MARKETPLACE)
public class MarketplaceController {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;

    private final MarketplaceQueryService service;

    /**
     * The feed. Sort is one of newest (default), price_low, price_per_sqm,
     * plots_remaining, the frontend's own set. A price filter applies within
     * one currency, NGN unless {@code currency} says otherwise.
     */
    @Operation(
            summary = "Browse published estates (no authentication)",
            description = """
                    The public feed. **No token required** — browsing never needs an account, only \
                    acting does. Rate-limited per client address.

                    **An estate appears only if all five conditions hold**, evaluated on every read \
                    rather than from a stored flag:

                    1. the developer published it;
                    2. the owning company's verification state is `verified`;
                    3. that company holds the `marketplacePublishing` entitlement;
                    4. its status is `active` — so a suspended *or offboarded* company's land is not \
                    for sale;
                    5. no blocking boundary conflict (an open HIGH, or one confirmed a duplicate).

                    Because it is evaluated at read time, a suspended company's listings disappear \
                    without being unpublished and return on reinstatement, with nothing republished.

                    `sort` is one of `newest` (default), `price_low`, `price_per_sqm`, \
                    `plots_remaining`. **A price filter only matches listings priced in the \
                    requested `currency`** (NGN unless given): comparing ₦20,000,000 against $50,000 \
                    would rank land by a meaningless number.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of listings; empty when nothing "
                    + "is currently eligible"),
            @ApiResponse(responseCode = "400", description = "Unknown `sort`, `titleType` or `intent` "
                    + "value", content = @Content()),
            @ApiResponse(responseCode = "429", description = "Rate limited; see `Retry-After`",
                    content = @Content())
    })
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<PageResponse<MarketplaceListingDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Currency currency,
            @RequestParam(required = false) BigDecimal minSize,
            @RequestParam(required = false) BigDecimal maxSize,
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
        return ResponseEntity.ok(service.search(filters, pageable(cursor, limit, sort)));
    }

    @Operation(
            summary = "One published estate (no authentication)",
            description = """
                    Full detail: description, amenities, price tiers, seller, and the estate's \
                    verification checks **with the source of each** — a registry lookup and a person \
                    reading a PDF are different claims, and a buyer deciding whether to part with \
                    money should see which.

                    **A check type that is absent was never checked.** Render it as unchecked; never \
                    as verified, and never as a clean bill of health.

                    Tier prices are the base price. A corner plot costs \
                    `price × (1 + cornerPremiumPct/100)`, computed by the client, never stored. \
                    `pricePerSqm` is a display comparison and is null for a unit-type tier.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The listing"),
            @ApiResponse(responseCode = "404", description = "No such estate, or it is not currently "
                    + "eligible — deliberately the same answer, so this cannot be used to discover "
                    + "that a hidden estate exists", content = @Content())
    })
    @SecurityRequirements
    @GetMapping("/{id}")
    public ResponseEntity<MarketplaceListingDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @Operation(
            summary = "An estate's boundary and plots as GeoJSON (no authentication)",
            description = """
                    A `FeatureCollection`: the estate boundary first, then every plot that has one, \
                    in **`[longitude, latitude]`** order (SRID 4326). The same shape the portal \
                    returns, so one map component renders both.

                    **Plot availability is only `AVAILABLE` or `UNAVAILABLE`.** Reserved and sold are \
                    deliberately indistinguishable: the difference is internal sales information and \
                    reveals sales velocity to competitors. Unavailable plots still appear, so the \
                    estate reads as a real place rather than a sales sheet.

                    Plots carry `priceTierId` and `isCorner` but no price — compute it from the \
                    tier and the estate's corner premium. A plot with no surveyed boundary is \
                    omitted rather than returned with a null geometry, which most mapping clients \
                    would draw at [0, 0].""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A GeoJSON FeatureCollection"),
            @ApiResponse(responseCode = "404", description = "No such estate, or not eligible",
                    content = @Content())
    })
    @SecurityRequirements
    @GetMapping("/{id}/geojson")
    public ResponseEntity<GeoJsonFeatureCollectionDto> geoJson(@PathVariable UUID id) {
        return ResponseEntity.ok(service.geoJson(id));
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

    /** Estate id as the last key so equal sort values still page deterministically. */
    private static Pageable pageable(String cursor, Integer limit, String sort) {
        int size = limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Sort order = switch (sort == null ? "newest" : sort) {
            case "price_low" -> Sort.by(Sort.Order.asc("fromPrice").nullsLast());
            case "price_per_sqm" -> Sort.by(Sort.Order.asc("fromPricePerSqm").nullsLast());
            case "plots_remaining" -> Sort.by(Sort.Order.desc("plotsRemaining"));
            case "newest" -> Sort.by(Sort.Order.desc("publishedAt").nullsLast());
            default -> throw new IllegalArgumentException(
                    "Unknown sort '" + sort + "'. Use newest, price_low, price_per_sqm or plots_remaining.");
        };
        Pageable base = PageResponses.pageable(cursor, size);
        return PageRequest.of(base.getPageNumber(), base.getPageSize(), order.and(Sort.by("estateId")));
    }
}
