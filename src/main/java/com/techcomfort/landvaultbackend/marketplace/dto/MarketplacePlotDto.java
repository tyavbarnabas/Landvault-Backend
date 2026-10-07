package com.techcomfort.landvaultbackend.marketplace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One plot on a published estate, for the list beside the map. The same
 * fields as a map feature, plus whether it has a surveyed boundary — a plot
 * without one can't be drawn, but is still for sale and must still be
 * choosable. No price: compute it from the tier and the corner premium, as
 * for the map.
 */
public record MarketplacePlotDto(
        UUID id,
        String plotNumber,
        String blockName,
        @Schema(description = "AVAILABLE or UNAVAILABLE — never why.") String availability,
        boolean isCorner,
        UUID priceTierId,
        BigDecimal nominalSizeSqm,
        @Schema(description = "From the surveyed boundary; null when there is none.") BigDecimal actualAreaSqm,
        @Schema(description = "False when the plot's boundary hasn't been surveyed yet: it isn't on the map, "
                + "and its exact position within the estate isn't confirmed. Tell the buyer.")
        boolean hasBoundary
) {
}
