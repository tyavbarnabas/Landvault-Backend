package com.techcomfort.landvaultbackend.marketplace.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A published estate as a buyer sees it: the feed item and the detail page,
 * shaped after the frontend's {@code Listing}.
 * <p>
 * Built only from the {@code marketplace_*} views, so it cannot carry
 * anything those views never selected. Known gaps against the frontend
 * contract, recorded in AGENTS.md rather than filled with guesses:
 * {@code paymentPlans} is absent because nothing stores payment plans yet.
 * {@code verified} is always true, and truthfully so: an estate whose
 * tenant isn't VERIFIED is not in the view at all.
 * <p>
 * {@code fromPrice} is the cheapest tier that still has a plot for sale (the
 * cheapest overall if everything is sold), in {@code fromPriceCurrency}.
 */
public record MarketplaceListingDto(
        UUID id,
        String name,
        String area,
        String city,
        String state,
        String description,
        List<String> amenities,
        /** The cover photo's URL, or null when the estate has none (one listed before photos were required). */
        String imageUrl,
        String titleType,
        /**
         * {@code pending} (declared by the developer, not yet checked),
         * {@code verified} (by HomeTrue) or {@code rejected}; null when no
         * title is declared. Never show {@code titleType} without it.
         */
        String titleStatus,
        /** When HomeTrue verified the title; null unless {@code titleStatus} is {@code verified}. */
        Instant titleVerifiedAt,
        Instant lastVerifiedDate,
        List<MarketplacePriceTierDto> priceTiers,
        BigDecimal cornerPremiumPct,
        String intent,
        Instant publishedDate,
        SellerDto seller,
        boolean verified,
        BigDecimal fromPrice,
        Currency fromPriceCurrency,
        long plotsRemaining,
        boolean hasMap,
        List<MarketplaceVerificationCheckDto> verificationChecks,

        /**
         * The declared charges, refund policy and default terms. Present on
         * the feed as well as the detail: FD-2 is explicit that the total is
         * not hidden behind a tap, and one code path is harder to get wrong
         * than two. Null only for a grandfathered estate that predates the
         * requirement.
         */
        CostDisclosureDto costDisclosure,

        /** Every photo, the cover first, then in the developer's order. Empty when there are none. */
        List<MarketplacePhotoDto> photos
) {
}
