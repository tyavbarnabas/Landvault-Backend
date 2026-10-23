package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Phase 5a: the buyer confirms withdrawing on the figures they were shown.
 * {@code refund} is the quote's total; if it no longer matches, nothing happens.
 */
public record WithdrawRequest(
        @Schema(description = "The `refund` from the quote the buyer saw.", example = "4730000.00")
        @NotNull BigDecimal refund,
        @Schema(description = "Optional; the developer sees it.", nullable = true,
                example = "My circumstances changed.") @Size(max = 500) String reason
) {
}
