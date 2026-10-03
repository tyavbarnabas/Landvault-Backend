package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /api/portal/estates/{id}} body. Every field is optional — left out
 * means unchanged. {@code footprint}, {@code published} and {@code branchId}
 * exist only so they can be <em>refused</em> rather than silently ignored:
 * each has consequences a plain-field edit must not carry, and its own route.
 */
public record UpdateEstateRequest(
        String name,
        @Schema(description = "Blank clears it.") String description,
        @Schema(description = "Blank clears it.") String area,
        @Schema(description = "Blank clears it.") String city,
        @Schema(description = "Cannot be cleared — every estate has a state.") String state,
        @Schema(description = "Blank clears it.") String address,
        @Schema(description = "Changes every corner plot's price at once. A buyer who has already reserved "
                + "keeps the price they agreed to.")
        @PositiveOrZero BigDecimal cornerPremiumPct,
        @Schema(description = "`development` or `investment`.") String intent,
        @Schema(description = "Replaces the whole list. Left out, the list is unchanged; an empty list clears it.")
        List<String> amenities,
        @Schema(description = "Refused — use POST /api/portal/estates/{id}/boundary.", hidden = true)
        Object footprint,
        @Schema(description = "Refused — use the publish/unpublish routes.", hidden = true)
        Boolean published,
        @Schema(description = "Refused — moving an estate between branches isn't supported.", hidden = true)
        UUID branchId
) {
}
