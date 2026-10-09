package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * FV-2: one purchase awaiting finance — what was agreed beside what Paystack
 * recorded, so the two can be compared before the plot is sold.
 */
public record FinanceQueueItemDto(
        UUID transactionId,
        String transactionReference,
        UUID estateId,
        String estateName,
        UUID plotId,
        @Schema(example = "Block A, Plot 10") String plotLabel,
        String buyerEmail,
        @Schema(description = "The price agreed at reservation.") BigDecimal expectedAmount,
        String currency,
        Instant awaitingSince,
        @Schema(description = "Paystack's record of the confirmed payment; null if none is on file.")
        GatewayRecord payment,
        @Schema(description = "True when Paystack's confirmed amount and currency equal the agreed price.")
        boolean amountsMatch
) {

    /**
     * What Paystack recorded. {@code last4}: the last 4 digits of what paid — a
     * card, or the paying account for a bank transfer ({@code channel} says
     * which). Never more.
     */
    public record GatewayRecord(
            String reference,
            BigDecimal amountPaid,
            String currency,
            String channel,
            Instant paidAt,
            String last4,
            String gatewayResponse
    ) {
    }
}
