package com.techcomfort.landvaultbackend.marketplace.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One tier of an estate's price list, matching the frontend's {@code PriceTier}.
 * <p>
 * {@code price} is the tier's own base price; a corner plot costs
 * {@code price × (1 + cornerPremiumPct/100)}, computed by the client from the
 * listing's {@code cornerPremiumPct}, never stored. {@code pricePerSqm} is a
 * display comparison and null for a unit-type tier. {@code sizeSqm} is null
 * for a unit-type tier too, where {@code label} carries the meaning.
 * <p>
 * {@code availability} is derived from {@code plotsRemaining} with the
 * frontend's own rule ({@code tiersFromPlots}): 0 is sold_out, 3 or fewer is
 * low_stock.
 */
public record MarketplacePriceTierDto(
        UUID id,
        BigDecimal sizeSqm,
        BigDecimal actualAreaSqm,
        String label,
        BigDecimal price,
        Currency currency,
        BigDecimal pricePerSqm,
        String availability,
        long plotsRemaining,

        /**
         * What this tier actually costs once declared charges are included —
         * the figure FD-2 exists to make visible. Null only for an estate
         * grandfathered in before disclosure was required.
         */
        TierCommitmentDto commitment
) {
}
