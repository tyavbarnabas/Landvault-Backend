package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One refund, as LandVault staff see it. {@code status}: sending, pending,
 * processing, needs-attention, processed, failed. When Paystack needs an
 * account (needs-attention) the buyer gives one: {@code account} is it (the
 * name as the bank returned it), and {@code accountNameMatchesBuyer} is a
 * WARNING, never a block — null until an account is given.
 */
public record RefundDto(UUID id, UUID paymentId, String paymentReference, UUID transactionId, String buyerName,
                        String buyerEmail, BigDecimal amount, String currency, String status, String gatewayMessage,
                        String reason, Instant createdAt, Instant expectedAt, Instant refundedAt,
                        RefundAccount account, Boolean accountNameMatchesBuyer) {

    public record RefundAccount(String bankName, String accountNumber, String accountName, Instant submittedAt,
                                Instant sentAt) {
    }
}
