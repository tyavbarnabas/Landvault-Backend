package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.RevocationTerms;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

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

                Revocation is a number, not text (phase 5b): `revocationAfterMonths`, an instalment that \
                many months unpaid. The free-text `revocationTrigger` and `onRevocationRefund` are no \
                longer accepted.

                Penalty tiers are declared as percentages here and published to buyers as **naira \
                amounts** — a percentage alone is not a disclosure.""")
public record DeclareDefaultTermsRequest(
        @NotNull @Min(RevocationTerms.MIN_AFTER_MONTHS) @Max(RevocationTerms.MAX_AFTER_MONTHS)
        @Schema(description = "An instalment plan may be revoked once an instalment is unpaid this many whole months "
                + "past its due date. At least 3. HomeTrue words the clause buyers see from this number; a revoked "
                + "buyer is refunded exactly as if they had withdrawn.", example = "6")
        Integer revocationAfterMonths,

        @Min(RevocationTerms.MIN_NOTICE_DAYS) @Schema(description = "Days of written notice before a revocation can "
                + "be confirmed, during which paying what is overdue stops it. At least 14; left out, 14.",
                nullable = true, example = "30")
        Integer revocationNoticeDays,

        @Positive @Schema(description = "Stored as declared data; nothing tracks it yet.",
                nullable = true, example = "3")
        Integer developmentDeadlineMonths,

        @NotNull Boolean transferRequiresConsent,

        @Valid @Schema(nullable = true) List<PenaltyTier> penaltyTiers,

        @Schema(description = "Whether late-payment penalties are returned, on the refund terms, to a buyer who "
                + "withdraws. Required when penalty tiers are declared; shown to buyers before they pay.",
                nullable = true, example = "false")
        Boolean penaltiesRefundable,

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
