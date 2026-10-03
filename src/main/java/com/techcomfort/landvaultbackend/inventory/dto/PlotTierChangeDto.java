package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Before and after for a tier move (IE-10). Both price and size can change,
 * and the size is the number on a deed, so both sides are reported.
 * Prices include the corner premium when the plot is a corner plot.
 */
public record PlotTierChangeDto(
        UUID plotId,
        UUID previousTierId,
        UUID tierId,
        @Schema(description = "Null only for a UNIT_TYPE plot that never had a land area.")
        BigDecimal previousNominalSizeSqm,
        BigDecimal nominalSizeSqm,
        BigDecimal previousPrice,
        BigDecimal price,
        Currency currency
) {
}
