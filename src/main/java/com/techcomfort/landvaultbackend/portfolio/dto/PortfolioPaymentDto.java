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
        @Schema(description = "`outright`, or on an instalment plan `deposit`, `instalment` or `balance`.")
        String type,
        @Schema(description = "`pending_verification` (Paystack confirmed it, finance hasn't) or `confirmed` (finance verified it).")
        String status,
        @Schema(description = "HomeTrue's receipt id (`/api/documents/receipts/{id}`); null until Paystack confirms the payment.", nullable = true)
        String receiptId,
        String rejectionReason,
        @Schema(description = "When finance verified it; null until then.")
        Instant verifiedAt
) {
}
