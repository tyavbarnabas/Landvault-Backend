package com.techcomfort.landvaultbackend.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The frontend's {@code OwnedPlot}, plus {@code transactionId} and
 * {@code titleStatus}. Every figure is computed here; the frontend does no
 * money arithmetic. Absent means absent: no surveyed area is never the
 * nominal size, and no corner premium is never a guessed percentage.
 */
@Schema(name = "OwnedPlot")
public record OwnedPlotDto(
        @Schema(description = "The owned plot's id; for a purchase still pending verification, its transaction id. "
                + "Either works on `GET /api/portfolio/plots/{id}`, and a transaction id keeps working once the "
                + "purchase is verified.")
        UUID id,
        UUID transactionId,
        UUID estateId,
        UUID plotId,
        @Schema(example = "Block A, Plot 12")
        String plotLabel,
        @Schema(description = "The estate's name.")
        String estate,
        @Schema(example = "Lugbe, Abuja, Federal Capital Territory (Abuja)")
        String location,
        @Schema(description = "What it was sold as (the tier's size). Null for a unit with no land area of its own.")
        BigDecimal sqm,
        @Schema(description = "The surveyed area, display only — never reconcile it with `sqm`. Null when unsurveyed.")
        BigDecimal actualSqm,
        boolean isCorner,
        @Schema(description = "The premium actually applied; null on a plot sold without one.")
        BigDecimal cornerPremiumPct,
        @Schema(description = "The price before any corner premium, as agreed.")
        BigDecimal basePrice,
        @Schema(description = "`C of O`, `R of O`, `Governor's Consent`, `Gazette`, or null when none is declared.")
        String titleType,
        @Schema(description = "`pending` (declared), `verified` (by HomeTrue) or `rejected`; null when none is declared.")
        String titleStatus,
        @Schema(description = "True only while HomeTrue has verified the title.")
        boolean titleVerified,
        @Schema(description = "`development` or `investment`.")
        String intent,
        @Schema(description = "`outright` or `installment`.")
        String plan,
        @Schema(description = "What the buyer pays: the agreed price, or on an instalment plan the plan total.")
        BigDecimal totalPrice,
        @Schema(description = "What finance has verified as paid. Zero while pending verification: equity doesn't "
                + "advance until finance signs off.")
        BigDecimal paidAmount,
        String currency,
        @Schema(description = "When finance verified the sale. Null while pending verification.")
        LocalDate acquiredDate,
        @Schema(description = "`pending_verification`, `installment_active` (deposit verified, plan running) or `completed` "
                + "(paid in full) today; the other statuses arrive with arrears, documents, upgrades and resale.")
        String status,
        @Schema(description = "On an active plan: the first unpaid instalment's due date. Null otherwise.", nullable = true)
        LocalDate nextDueDate,
        @Schema(description = "On an active plan: the first unpaid instalment's amount. Null otherwise.", nullable = true)
        BigDecimal nextDueAmount,
        List<PortfolioPaymentDto> payments,
        Integer installmentMonths,
        Integer installmentsPaid,
        @Schema(description = "Only while `in_arrears`; null today.")
        ArrearsDto arrears,
        @Schema(description = "Null: restructuring isn't built yet.")
        String restructureStatus,
        @Schema(description = "The owned plot this one replaced (an upgrade). Null today.")
        UUID supersedes
) {
}
