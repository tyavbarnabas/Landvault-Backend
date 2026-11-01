package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Sell my plot, phase 5a: a buyer holds a resale plot to buy it. */
@Schema(name = "HoldResale")
public record HoldResaleRequest(
        @Schema(description = "The resale listing's id, as on `/api/marketplace/resales/{id}`.")
        @NotNull UUID listingId) {
}
