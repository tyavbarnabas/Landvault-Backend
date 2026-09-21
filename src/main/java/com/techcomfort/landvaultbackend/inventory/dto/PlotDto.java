package com.techcomfort.landvaultbackend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code nominalSizeSqm} is what the plot is sold as (from its tier);
 * {@code actualAreaSqm} is what the survey says, computed from the footprint
 * in square metres. <strong>Null actual area means no boundary yet</strong> —
 * it never falls back to the nominal figure.
 */
public record PlotDto(
        UUID id,
        UUID estateId,
        UUID blockId,
        UUID priceTierId,
        String plotNumber,
        boolean isCorner,
        String status,
        String intent,
        String propertyType,
        String listingIntent,
        String orientation,
        BigDecimal nominalSizeSqm,
        BigDecimal actualAreaSqm,
        boolean hasFootprint
) {
}
