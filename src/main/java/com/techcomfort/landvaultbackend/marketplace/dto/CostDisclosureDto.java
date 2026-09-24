package com.techcomfort.landvaultbackend.marketplace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Everything a buyer should know about cost before committing — the charges
 * beyond the price, what withdrawing returns, and what falling behind costs.
 * <p>
 * The platform's claim here is narrow and worth stating exactly: it does not
 * prevent fraud, and it does not regulate terms. Both companies whose real
 * letters this was modelled on disclosed every charge, and both appear to
 * operate legally. <strong>The failure was one of sequence</strong> — the
 * charges arrived after the buyer had already paid. This moves them to where
 * they can still affect the decision.
 */
@Schema(
        name = "CostDisclosure",
        description = """
                The full cost of this listing, before committing to it.

                Everything here was declared by the developer and is published unchanged. The \
                platform **discloses; it does not regulate** — no amount is capped, flagged or \
                judged.

                `fees` may be empty, which means the developer stated there are no charges beyond \
                the land price. An estate that has declared nothing at all cannot be listed, so an \
                empty list here is a statement, never a gap.""")
public record CostDisclosureDto(
        @Schema(description = "Every declared charge. Empty means the developer declared none.")
        List<PublicFeeDto> fees,

        @Schema(description = "Null when this estate predates the disclosure requirement.",
                nullable = true)
        ExitCostsDto exitCosts
) {
}
