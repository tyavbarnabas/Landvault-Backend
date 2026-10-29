package com.techcomfort.landvaultbackend.marketplace.dto;

import com.techcomfort.landvaultbackend.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A plot a buyer is selling, as anyone may see it (sell my plot, phase 3).
 * Deliberately nothing about the seller: not who they are, what they paid or
 * still owe, how they bought, or whether the price is the developer's.
 */
@Schema(description = "A plot for resale. Never names the seller or what they paid; a fixed price, no offers.")
public record MarketplaceResaleListingDto(
        UUID id,
        /** The plot on the estate map (the same id as `marketplace_plots`), so the map can highlight it. */
        UUID plotId,
        UUID estateId,
        String estateName,
        /** "Block A, Plot 7", as letters print it; just "Plot 7" when the estate has no blocks. */
        String plotLabel,
        String blockName,
        String plotNumber,
        String area,
        String city,
        String state,
        /** The estate's intent. */
        String intent,
        /** The estate's cover photo; null when it has none. */
        String imageUrl,
        /** The estate's title: a plot's title is its estate's. Read with titleStatus. */
        String titleType,
        String titleStatus,
        Instant titleVerifiedAt,
        /** The developer company. The seller is a buyer and is never named. */
        String developerName,
        boolean isCorner,
        BigDecimal sizeSqm,
        /** The surveyed area; null when the plot has no boundary. */
        BigDecimal actualAreaSqm,
        boolean hasBoundary,
        /** The price, fixed. */
        BigDecimal price,
        Currency currency,
        /** Null when the plot has no size (a built unit). */
        BigDecimal pricePerSqm,
        Instant listedAt,
        @Schema(description = "Whether the estate's terms say a transfer needs the developer's consent; null "
                + "when the estate declares no default terms.")
        Boolean transferRequiresConsent
) {
}
