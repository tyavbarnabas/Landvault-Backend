package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Sell my plot, phase 2: change a listing's asking price. */
@Schema(name = "UpdateResaleListing")
public record UpdateListingRequest(@NotNull BigDecimal price) {
}
