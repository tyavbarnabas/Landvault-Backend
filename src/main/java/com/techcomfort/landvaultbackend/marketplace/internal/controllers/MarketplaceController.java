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
public class MarketplaceController {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;

    private final MarketplaceQueryService service;

    /**
     * The feed. Sort is one of newest (default), price_low, price_per_sqm,
     * plots_remaining, the frontend's own set. A price filter applies within
     * one currency, NGN unless {@code currency} says otherwise.
     */
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

    @GetMapping("/{id}")
    public ResponseEntity<MarketplaceListingDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

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
