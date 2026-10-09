package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payout attempt. {@code status}: sending, awaiting_otp, pending,
 * success, failed, reversed, cancelled. {@code gatewayMessage} is Paystack's
 * own words where it gave any (a failure reason, "requires OTP", …).
 * {@code bankName}/{@code accountLast4} are the account THIS payout went to.
 * A sale above Paystack's per-transfer cap is paid in equal parts: this is
 * part {@code partNumber} of {@code partCount}, and {@code amount} is the part.
 * {@code reviewReason} is set when a person must look at it (TR-3).
 */
public record PayoutDto(UUID id, UUID transactionId, UUID tenantId, BigDecimal amount, String currency,
                        int partNumber, int partCount, String status, String reference, String gatewayMessage,
                        String bankName,
                        String accountLast4, Instant createdAt, Instant transferredAt, String reviewReason) {
}
