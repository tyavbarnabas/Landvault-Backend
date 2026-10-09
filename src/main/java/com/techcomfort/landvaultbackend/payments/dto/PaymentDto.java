package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment attempt, as the buyer sees it. Never carries a key, an access secret or card details. */
public record PaymentDto(
        UUID id,
        UUID transactionId,
        @Schema(description = "Our reference, which Paystack echoes back.") String reference,
        @Schema(description = "initialized, succeeded, failed, abandoned or mismatched") String status,
        BigDecimal amount,
        String currency,
        @Schema(description = "Paystack's payment page. Send the buyer here; they return to the frontend afterwards.")
        String authorizationUrl,
        Instant createdAt,
        @Schema(description = "Paystack's own words for the outcome, e.g. \"Approved\" or \"Insufficient Funds\". "
                + "Show it: it tells the buyer whether to retry or use another method.")
        String gatewayResponse,
        @Schema(description = "How it was paid: card, bank_transfer, ussd...") String channel,
        Instant paidAt,
        @Schema(description = "True when our team must look at this payment by hand — e.g. the money arrived "
                + "after the purchase had been abandoned. Tell the buyer we'll be in touch.")
        boolean underReview
) {
}
