package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * IE-2: what an edit to this tier would reach, before anyone makes it.
 * <p>
 * The counts are the same shape an estate's plot counts use, so "120 plots,
 * 8 reserved" reads identically wherever it appears.
 */
@Schema(name = "PriceTierImpact", description = """
        How many plots point at this tier, by status. A price change applies to all of them, \
        but a buyer who has already reserved keeps the price captured at reservation. A size \
        change applies to available plots only.""")
public record PriceTierImpactDto(UUID tierId, PlotCountsDto plots) {
}
