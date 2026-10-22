package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        @Schema(description = "What this payment must be: the price agreed at reservation, or — for an "
                + "instalment plan — the deposit from its fixed schedule.") BigDecimal expectedAmount,
        String currency,
        Instant awaitingSince,
        @Schema(description = "Paystack's record of the confirmed payment; null if none is on file.")
        GatewayRecord payment,
        @Schema(description = "True when Paystack's confirmed amount and currency equal `expectedAmount`.")
        boolean amountsMatch,
        @Schema(description = """
                The estate's allocation letter is approved, so verifying issues the buyer's letter. When false, \
                verifying is refused (`LETTER_NOT_APPROVED`) until the Executive Director approves it.""")
        boolean letterApproved,
        @Schema(description = "`full_price`; on an instalment plan `deposit` (verifying holds the plot on the plan and "
                + "issues the provisional allocation letter), `instalment`, or `balance` (everything left, paid off). "
                + "Verifying the last instalment, or a balance, sells the plot and issues the final letter.")
        String paymentFor,
        @Schema(description = "The instalment plan's name; null when paying in full.", nullable = true)
        String planName,
        @Schema(description = "For an `instalment`: which one, e.g. 3.", nullable = true)
        Integer instalmentSequence,
        @Schema(description = "For an `instalment` or `balance`: how many the plan has, e.g. 12.", nullable = true)
        Integer instalmentCount,
        @Schema(description = "For an `instalment`: when it falls due.", nullable = true)
        LocalDate dueDate
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
