package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5a: what withdrawing from an instalment plan returns, payment by
 * payment — a quote while the plan runs, the record once withdrawn.
 */
@Schema(name = "Withdrawal")
public record WithdrawalDto(
        UUID transactionId,

        @Schema(description = "`on_plan`: a quote — nothing has happened yet. `withdrawn`: what was settled. "
                + "`revoked` (phase 5b): the developer revoked the plan, settled exactly as a withdrawal.",
                example = "on_plan")
        String status,

        @Schema(description = "When the plan ended: withdrawn, or (`revoked`) revoked.", nullable = true)
        Instant withdrawnAt,

        @Schema(description = "The refund terms copied onto the purchase when it opened.") Terms terms,

        @Schema(description = "Everything paid on the plan that counts (deposit, instalments, pay-off, penalties).")
        BigDecimal paid,

        @Schema(description = "What comes back to the buyer in all. Confirming a withdrawal names this figure.")
        BigDecimal refund,

        @Schema(description = "What the developer keeps under the declared terms.") BigDecimal developerKeeps,

        @Schema(example = "NGN") String currency,

        List<Line> payments
) {

    /**
     * @param deductionPct  the developer's declared deduction; null when the purchase predates escrow terms
     *                      (then everything paid comes back, penalties the developer keeps aside)
     * @param appliesTo     {@code amount_paid} or {@code total_price} (a fixed sum on the land price)
     */
    public record Terms(@Schema(nullable = true) BigDecimal deductionPct,
                        @Schema(nullable = true, example = "amount_paid") String appliesTo,
                        boolean penaltiesRefundable) {
    }

    /**
     * One payment and how it splits.
     *
     * @param awaitingFinance Paystack confirmed it but the developer's finance hasn't verified it: the buyer
     *                        gets their part now, the developer's share waits for finance (paid out once
     *                        verified; refunded too if finance rejects it)
     * @param refundStatus    once withdrawn: the newest refund's status ({@code pending}, {@code processing},
     *                        {@code needs-attention}, {@code processed}, {@code failed}), null before one starts
     */
    public record Line(String reference,
                       @Schema(example = "instalment") String paymentFor,
                       @Schema(nullable = true) Integer instalmentSequence,
                       BigDecimal amount,
                       BigDecimal penaltyAmount,
                       BigDecimal developerKeeps,
                       BigDecimal refund,
                       boolean awaitingFinance,
                       @Schema(nullable = true) String refundStatus) {
    }
}
