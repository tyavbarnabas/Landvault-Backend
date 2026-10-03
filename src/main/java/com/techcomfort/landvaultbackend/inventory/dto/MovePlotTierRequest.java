package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code PUT .../plots/{plotId}/tier} body (IE-10). */
public record MovePlotTierRequest(
        @Schema(description = "The tier to move the plot to. Must be on the same estate and in the same currency.")
        @NotNull UUID tierId,

        @Schema(description = "UNIT_TYPE tiers only: the plot's own land area. Left out, the plot keeps its "
                + "current size. Refused for a LAND_SIZE tier, whose size always applies.")
        @Positive BigDecimal nominalSizeSqmOverride
) {
}
