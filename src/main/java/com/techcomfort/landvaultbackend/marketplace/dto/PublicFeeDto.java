package com.techcomfort.landvaultbackend.marketplace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** One declared charge, as a buyer sees it before committing. */
@Schema(
        name = "PublicFee",
        description = """
                A charge beyond the land price.

                **`isMandatory` means the buyer has no path that avoids it.** A fee charged only \
                "on construction start" is still mandatory where the terms require building — which \
                is the case in the real allocation letters this was modelled on, and the reading a \
                buyer would otherwise get wrong.

                A variable fee shows `amountMin`/`amountMax` with `variationBasis` stating why it \
                varies. That basis is often genuine — building-material prices really do move — \
                which is why a range is permitted and silence is not.""")
public record PublicFeeDto(
        @Schema(example = "infrastructure") String feeType,
        @Schema(nullable = true) String label,
        MoneyRangeDto amount,
        String currency,
        boolean isFixed,
        @Schema(description = "Why a variable fee varies. Never null when `isFixed` is false.",
                nullable = true)
        String variationBasis,
        @Schema(description = "When it falls due.", example = "on_construction_start") String dueTrigger,
        boolean refundable,
        boolean isMandatory,
        @Schema(nullable = true) String notes
) {
    /** Convenience for a fixed fee, where the "range" is a single figure. */
    public static MoneyRangeDto range(BigDecimal low, BigDecimal high) {
        boolean isRange = low != null && high != null && low.compareTo(high) != 0;
        return new MoneyRangeDto(low, high, isRange);
    }
}
