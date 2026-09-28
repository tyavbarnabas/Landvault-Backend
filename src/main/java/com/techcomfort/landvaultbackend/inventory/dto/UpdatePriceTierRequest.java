package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * {@code PUT .../price-tiers/{tierId}} body. Every field is optional: one
 * left out stays as it is.
 * <p>
 * {@code tierType} and {@code currency} are accepted only so a client can
 * echo a tier back unchanged — sending a <em>different</em> value is refused
 * rather than ignored, because a silently dropped field reads as success.
 */
@Schema(name = "UpdatePriceTierRequest", description = """
        Change a tier's price, label or size. Fields left out are unchanged.

        **A price change reaches every plot on the tier** — that is what tiers are for. A \
        buyer who has already reserved is unaffected: their price was captured when they took \
        the hold and is never recomputed.

        **A size change reaches only available plots.** A plot's size is what appears on its \
        deed, and a reservation locks the price but not the size, so reserved and sold plots \
        keep the size they were sold at. A reserved plot that later returns to the market \
        picks up the tier's current size then.

        `tierType` and `currency` cannot change: a different type or currency is a different \
        tier. Sending the current value is accepted; sending another is refused.""")
public record UpdatePriceTierRequest(

        @Schema(description = "The developer's own price for this band. Never derived from a per-sqm rate.",
                example = "25000000")
        @Positive BigDecimal price,

        @Schema(description = "Display label. An empty string clears it.", example = "Standard 450 sqm")
        String label,

        @Schema(description = "`LAND_SIZE` tiers only. Applied to available plots only.", example = "450")
        @Positive BigDecimal sizeSqm,

        @Schema(description = "Immutable — only the current value is accepted.", example = "LAND_SIZE")
        String tierType,

        @Schema(description = "Immutable — only the current value is accepted.", example = "NGN")
        Currency currency
) {
}
