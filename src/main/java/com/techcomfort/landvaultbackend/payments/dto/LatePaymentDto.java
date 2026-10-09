package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money that arrived after its purchase was abandoned. {@code plotAvailable}:
 * the plot can still be allocated; when false, refund is the only choice.
 * {@code amountsMatch}: Paystack confirmed exactly the agreed price — required
 * to allocate.
 */
public record LatePaymentDto(UUID transactionId, String transactionReference, String estateName, String plotLabel,
                             String buyerName, String buyerEmail, String paymentReference, BigDecimal agreedPrice,
                             BigDecimal amountPaid, String currency, String channel, Instant paidAt,
                             boolean plotAvailable, boolean amountsMatch) {
}
