package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One plot within a batch create.
 * <p>
 * There is deliberately <strong>no nominal size field</strong>: a plot's
 * nominal size is its tier's, copied at creation. Accepting one would let a
 * caller supply a size that contradicts the tier the plot is priced by,
 * leaving the invoice and the deed disagreeing.
 * <p>
 * The one exception is {@code nominalSizeSqmOverride}, accepted <em>only</em>
 * for a {@code UNIT_TYPE} tier, which has no size of its own — a terrace on
 * its own plot has a real land area worth recording, while an apartment has
 * none and leaves it null.
 * <p>
 * {@code actualAreaSqm} is never accepted: it is computed from
 * {@code footprint} via {@code ST_Area(footprint::geography)} on write.
 */
public record CreatePlotRequest(
        @NotBlank String plotNumber,
        UUID blockId,
        @NotNull UUID priceTierId,
        Boolean isCorner,
        @NotBlank String status,
        String intent,
        String propertyType,
        String listingIntent,
        String orientation,
        BigDecimal nominalSizeSqmOverride,
        @Valid GeoJsonPolygonDto footprint
) {
}
