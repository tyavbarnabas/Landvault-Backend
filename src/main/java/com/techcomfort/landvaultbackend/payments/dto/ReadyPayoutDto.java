package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A verified sale not yet paid in full, with nothing in flight. {@code amount}
 * is the sale; {@code amountOwed} what is left. A sale above Paystack's
 * per-transfer cap is paid in {@code partCount} equal parts: {@code partsPaid}
 * done, {@code nextPart} next, for {@code nextPartAmount}. {@code blockers} is
 * empty when it can be paid now (otherwise NO_APPROVED_PAYOUT_ACCOUNT,
 * COMPANY_NOT_ACTIVE, CURRENCY_NOT_SUPPORTED). {@code lastAttempt} is the most
 * recent attempt, e.g. a failed one.
 */
public record ReadyPayoutDto(UUID transactionId, String transactionReference, UUID tenantId, String companyName,
                             String estateName, String plotLabel, BigDecimal amount, String currency,
                             BigDecimal amountOwed, Integer partCount, Integer partsPaid, Integer nextPart,
                             BigDecimal nextPartAmount, Instant verifiedAt, PayoutAccount payoutAccount,
                             List<String> blockers, PayoutDto lastAttempt) {

    /** Where the money would go. */
    public record PayoutAccount(String bankName, String accountLast4, String accountName) {
    }
}
