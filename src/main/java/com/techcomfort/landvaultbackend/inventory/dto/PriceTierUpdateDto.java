package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * The tier after an edit, plus — only when its size changed — what that size
 * change actually reached.
 */
@Schema(name = "PriceTierUpdate")
public record PriceTierUpdateDto(
        PriceTierDto tier,

        @Schema(description = "Present only when `sizeSqm` changed; null otherwise.")
        SizeChange sizeChange
) {

    /**
     * {@code keptPreviousSize} is keyed by plot status wire value and only
     * carries statuses that occur — the same convention as
     * {@link PlotCountsDto#byStatus()}.
     */
    @Schema(name = "PriceTierSizeChange")
    public record SizeChange(
            BigDecimal previousSizeSqm,
            BigDecimal newSizeSqm,
            @Schema(description = "Available plots now carrying the new size.")
            long plotsUpdated,
            @Schema(description = "Reserved or sold plots that kept their previous size, by status.")
            PlotCountsDto keptPreviousSize,
            String note
    ) {
    }
}
