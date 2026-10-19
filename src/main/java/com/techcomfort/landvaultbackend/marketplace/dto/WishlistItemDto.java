package com.techcomfort.landvaultbackend.marketplace.dto;

import com.techcomfort.landvaultbackend.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One saved listing. The current figures are null while the listing is off the marketplace. */
@Schema(name = "WishlistItem")
public record WishlistItemDto(
        @Schema(example = "primary")
        String listingType,
        @Schema(description = "For `primary`, the estate id.")
        UUID listingId,
        @Schema(description = "The listing's current name while it's on the marketplace, otherwise its name when saved.")
        String name,
        Instant savedAt,
        @Schema(description = "The listing's from-price when saved. Only for marking a change since — never show it "
                + "as the price. Compare with `currentPrice` only when `currency` equals `currentCurrency`.",
                nullable = true)
        BigDecimal priceAtSave,
        @Schema(nullable = true)
        Currency currency,
        @Schema(description = "Whether the listing is on the marketplace now. False never says why.")
        boolean available,
        @Schema(description = "The from-price now. Null when not available.", nullable = true)
        BigDecimal currentPrice,
        @Schema(nullable = true)
        Currency currentCurrency,
        @Schema(description = "Plots for sale now; 0 means listed but sold out. Null when not available.",
                nullable = true)
        Long plotsRemaining
) {
}
