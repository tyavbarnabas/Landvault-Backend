package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A plot as read back, with its price computed rather than stored — see
 * {@code Plot} and {@code Estate.cornerPremiumPct} for why no corner price
 * exists as a column anywhere.
 * <p>
 * The three price fields are deliberately all present rather than just the
 * final figure: {@code basePrice} is the tier's own price,
 * {@code cornerPremiumPct} the modifier applied (null when this plot is not
 * a corner), and {@code price} the result. A UI showing only {@code price}
 * on a corner plot would be showing a number that matches no tier on the
 * price list, with nothing to explain the difference.
 * <p>
 * {@code pricePerSqm} is a <strong>displayed comparison, never an input to
 * pricing</strong> — it is null whenever {@code nominalSizeSqm} is
 * (a {@code UNIT_TYPE} tier: an apartment has no exclusive land area, and
 * dividing by nothing to produce a rate would fabricate one).
 */
public record PlotDetailDto(
        UUID id,
        UUID estateId,
        UUID blockId,
        String blockName,
        UUID priceTierId,
        String priceTierLabel,
        String plotNumber,
        boolean isCorner,
        String status,
        String intent,
        String propertyType,
        String listingIntent,
        String orientation,
        BigDecimal nominalSizeSqm,
        BigDecimal actualAreaSqm,
        boolean hasFootprint,
        BigDecimal basePrice,
        BigDecimal cornerPremiumPct,
        BigDecimal price,
        Currency currency,
        BigDecimal pricePerSqm
) {
}
