package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code PUT /api/portal/estates/{id}/refund-terms}. Writes a new version.
 */
@Schema(
        name = "DeclareRefundTermsRequest",
        description = """
                State what a buyer gets back if they withdraw. Required before a new estate can be \
                listed.

                Some developers will object that their terms look harsh beside a competitor's. The \
                terms are identical either way — one platform simply says so beforehand.""")
public record DeclareRefundTermsRequest(
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Schema(example = "20.00") BigDecimal deductionPct,

        @NotNull @PositiveOrZero @Schema(example = "90") Integer processingDays,

        @NotBlank @Schema(description = "`amount_paid` or `total_price` — state it; it changes the "
                + "figure materially.", example = "total_price")
        String appliesTo,

        @Schema(description = "Fee types never returned, e.g. a non-refundable application fee.",
                nullable = true)
        List<String> nonRefundableFeeTypes,

        @Schema(nullable = true) String notes
) {
}
