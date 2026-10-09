package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund, as the buyer sees it. {@code needsAccount}: Paystack couldn't
 * return the money on its own — ask the buyer for a bank account in their own
 * name. {@code status}: pending, processing, needs-attention, processed, failed.
 */
public record BuyerRefundDto(String status, BigDecimal amount, String currency, boolean needsAccount,
                             String accountBankName, String accountLast4, Instant expectedAt, Instant refundedAt) {
}
