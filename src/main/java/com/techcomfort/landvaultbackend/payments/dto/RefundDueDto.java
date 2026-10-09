package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment owed back to its buyer with no refund under way. {@code amount}
 * is what will be returned — the full amount paid (decided with the user).
 * {@code lastAttempt} is a previous failed refund, if any.
 */
public record RefundDueDto(UUID paymentId, String paymentReference, UUID transactionId, String buyerName,
                           String buyerEmail, BigDecimal amount, String currency, String channel, String last4,
                           Instant paidAt, String reason, Instant requestedAt, RefundDto lastAttempt) {
}
