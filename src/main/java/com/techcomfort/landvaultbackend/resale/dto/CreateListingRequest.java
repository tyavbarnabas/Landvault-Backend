package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Sell my plot, phase 2: list a plot. */
@Schema(name = "CreateResaleListing")
public record CreateListingRequest(
        @NotNull @Schema(description = "The owned-plot or transaction id, as on the portfolio.") UUID plotId,
        @Schema(nullable = true, description = "Your asking price, whole kobo; not below your agreed price on a plan. "
                + "Leave it out on an early sale — its price is the developer's.") BigDecimal price
) {
}
