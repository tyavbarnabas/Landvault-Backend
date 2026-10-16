package com.techcomfort.landvaultbackend.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The frontend's {@code Payment}, for a plot's history. */
@Schema(name = "PortfolioPayment")
public record PortfolioPaymentDto(
        @Schema(description = "Our payment reference.")
        String id,
        LocalDate date,
        BigDecimal amount,
        String currency,
        @Schema(description = "`outright` today.")
        String type,
        @Schema(description = "`pending_verification` (Paystack confirmed it, finance hasn't) or `confirmed` (finance verified it).")
        String status,
        @Schema(description = "The payment reference until receipts are issued — not yet a receipt number.")
        String receiptId,
        String rejectionReason,
        @Schema(description = "When finance verified it; null until then.")
        Instant verifiedAt
) {
}
