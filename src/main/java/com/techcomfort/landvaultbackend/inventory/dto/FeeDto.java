package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One declared charge, as read back. Enum-typed fields are wire strings —
 * a public DTO must not expose an internal type (AGENTS.md).
 */
@Schema(
        name = "EstateFee",
        description = """
                A charge a buyer commits to beyond the land price.

                **A fixed fee carries `amount`; a variable one carries `amountMin`/`amountMax` and a \
                `variationBasis` saying why it varies.** Never both, and never a midpoint — a \
                midpoint is a figure nobody quoted.

                **`isMandatory` means unavoidable, not "stated as unconditional".** A fee charged \
                only "if you build" is mandatory where the terms also require building, which is \
                the case in every source letter this was modelled on.""")
public record FeeDto(
        @Schema(description = "`application`, `setting_out`, `infrastructure`, "
                + "`construction_supervision`, `facility_management`, `survey`, `legal` or `other`.",
                example = "infrastructure")
        String feeType,

        @Schema(description = "Required for `other`.", nullable = true) String label,

        @Schema(description = "Set only when the fee is fixed.", nullable = true) BigDecimal amount,
        @Schema(nullable = true) BigDecimal amountMin,
        @Schema(nullable = true) BigDecimal amountMax,

        @Schema(example = "NGN") String currency,
        boolean isFixed,

        @Schema(description = "Why a variable fee varies. Never null when `isFixed` is false.",
                nullable = true)
        String variationBasis,

        @Schema(description = "`at_application`, `at_allocation`, `on_construction_start`, "
                + "`on_milestone`, `annual` or `before_occupation`.", example = "on_construction_start")
        String dueTrigger,

        boolean refundable,

        @Schema(description = "Whether the buyer has any path that avoids this charge.")
        boolean isMandatory,

        @Schema(nullable = true) String notes
) {
}
