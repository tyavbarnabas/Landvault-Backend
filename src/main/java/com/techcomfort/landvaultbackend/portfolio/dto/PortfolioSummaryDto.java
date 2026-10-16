package com.techcomfort.landvaultbackend.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * A buyer's totals, one entry per currency — never summed across currencies
 * (PF-3). Only plots still owned count: transferred and superseded records
 * are history (PF-2). Purchases pending verification are counted separately.
 */
@Schema(name = "PortfolioSummary")
public record PortfolioSummaryDto(
        List<CurrencyTotals> byCurrency,
        @Schema(description = "Paid purchases finance hasn't verified yet. Not in the totals.")
        int awaitingVerification
) {

    public record CurrencyTotals(
            String currency,
            @Schema(description = "The agreed prices of the plots owned, added up.")
            BigDecimal totalValue,
            @Schema(description = "What finance has verified as paid on them.")
            BigDecimal totalPaid,
            int count
    ) {
    }
}
