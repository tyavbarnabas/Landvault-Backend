package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code PUT /api/portal/estates/{id}/fees}. Replaces the schedule by
 * writing a new version; the previous one is retained.
 */
@Schema(
        name = "DeclareFeesRequest",
        description = """
                Declare every charge beyond the land price.

                **Sending an empty list is a valid declaration** — it states that there are no extra \
                charges, and permits publication. Not sending the request at all does not.

                A fee is either fixed (`amount`) or variable (`amountMin`, `amountMax` and a \
                `variationBasis`). The platform does not judge amounts: no cap, no warning, no \
                threshold. It only requires that they be stated before a buyer can commit.""")
public record DeclareFeesRequest(
        @Schema(description = "Every charge. Empty means none.")
        @NotNull @Valid List<FeeDeclaration> fees
) {

    @Schema(name = "FeeDeclaration")
    public record FeeDeclaration(
            @NotBlank @Schema(example = "infrastructure") String feeType,
            @Schema(description = "Required for `other`.", nullable = true) String label,
            @PositiveOrZero @Schema(nullable = true) BigDecimal amount,
            @PositiveOrZero @Schema(nullable = true) BigDecimal amountMin,
            @PositiveOrZero @Schema(nullable = true) BigDecimal amountMax,
            @NotBlank @Schema(example = "NGN") String currency,
            @NotNull Boolean isFixed,
            @Schema(description = "Required when `isFixed` is false.", nullable = true)
            String variationBasis,
            @NotBlank @Schema(example = "on_construction_start") String dueTrigger,
            @NotNull Boolean refundable,
            @NotNull @Schema(description = "True when the buyer has no path that avoids this charge.")
            Boolean isMandatory,
            @Schema(nullable = true) String notes
    ) {
    }
}
