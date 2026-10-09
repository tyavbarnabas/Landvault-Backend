package com.techcomfort.landvaultbackend.payments.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One payout-account submission, as the company sees it. {@code accountName}
 * is what the bank returned. {@code status}: pending, approved, rejected,
 * withdrawn, superseded. Only {@code approved} is ever paid to.
 */
public record SettlementAccountDto(UUID id, String bankCode, String bankName, String accountNumber,
                                   String accountName, String currency, String status, Instant submittedAt,
                                   Instant decidedAt, String decisionNote) {
}
