package com.techcomfort.landvaultbackend.checkout;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * How a purchase's payment stands, as its buyer sees it. Filled in by the
 * payments module through {@link PaymentSummaryProvider}: checkout can't read
 * payments itself (payments already depends on checkout).
 */
@Schema(name = "PaymentSummary", description = """
        The purchase's payment: the successful attempt if there is one, otherwise the most recent. Null on the \
        purchase until the buyer first presses pay.""")
public record PaymentSummary(
        @Schema(description = "Our payment reference — the key to `/api/payments/{reference}/…`.") String reference,
        @Schema(description = "initialized, succeeded, failed, abandoned or mismatched") String status,
        @Schema(description = "What Paystack confirmed; null until it did.", nullable = true) BigDecimal amountPaid,
        @Schema(nullable = true) Instant paidAt,
        @Schema(description = "A person is looking at this payment — see `reviewKind` for why.") boolean underReview,
        @Schema(description = """
                Why it's under review: `late_payment` — paid after the reservation ended; the developer will \
                allocate the plot or refund it. `refund_due` — the money is being returned (the developer \
                rejected the purchase, or a late payment wasn't allocated). Null when not under review.""",
                nullable = true) String reviewKind,
        @Schema(description = """
                The refund's status if one has started: sending, pending, processing, needs-attention, \
                processed, failed. Null when none has.""", nullable = true) String refundStatus
) {
}
