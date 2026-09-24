package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code PUT /api/portal/estates/{id}/default-terms}. Writes a new version,
 * penalty tiers included.
 */
@Schema(
        name = "DeclareDefaultTermsRequest",
        description = """
                State the consequences of falling behind, and the restrictions on transfer.

                Penalty tiers are declared as percentages here and published to buyers as **naira \
                amounts** — a percentage alone is not a disclosure.""")
public record DeclareDefaultTermsRequest(
        @NotBlank @Schema(example = "Payment more than 12 months overdue, or failure to commence "
                + "development within the stated period.")
        String revocationTrigger,

        @PositiveOrZero @Schema(nullable = true, example = "30") Integer revocationNoticeDays,

        @NotBlank @Schema(description = "What happens to money already paid. Required — both source "
                + "letters omitted exactly this.",
                example = "Payments to date are refunded less a 20% administrative charge.")
        String onRevocationRefund,

        @Positive @Schema(description = "Stored as declared data; nothing tracks it yet.",
                nullable = true, example = "3")
        Integer developmentDeadlineMonths,

        @NotNull Boolean transferRequiresConsent,

        @Valid @Schema(nullable = true) List<PenaltyTier> penaltyTiers,

        @Schema(nullable = true) String notes
) {

    @Schema(name = "PenaltyTierDeclaration")
    public record PenaltyTier(
            @NotNull @Positive @Schema(example = "3") Integer monthsLate,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Schema(example = "5.00")
            BigDecimal penaltyPct
    ) {
    }
}
