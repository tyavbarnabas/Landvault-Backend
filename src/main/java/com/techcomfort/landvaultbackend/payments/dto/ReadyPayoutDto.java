package com.techcomfort.landvaultbackend.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A sale with money owed to the developer and nothing in flight. {@code amount}
 * is the sale's land price; {@code amountOwed} what is owed now and unpaid.
 * <p>
 * What is owed comes in tranches, paid in order: {@code tranche} is the next
 * one — {@code sale} (outright: the whole price), {@code share} (an instalment
 * plan: the developer's share of the verified payment {@code sharePaymentId},
 * by the refund deduction the buyer was offered) or {@code release} (what
 * HomeTrue held in escrow, once the plan completes). {@code heldInEscrow} is
 * what HomeTrue holds for a plan still running (null for outright).
 * <p>
 * A tranche above Paystack's per-transfer cap is paid in {@code partCount}
 * equal parts: {@code partsPaid} done, {@code nextPart} next, for
 * {@code nextPartAmount}. {@code blockers} is empty when it can be paid now
 * (otherwise NO_APPROVED_PAYOUT_ACCOUNT, COMPANY_NOT_ACTIVE,
 * CURRENCY_NOT_SUPPORTED). {@code lastAttempt} is the sale's most recent
 * attempt, e.g. a failed one. {@code verifiedAt} is when the developer's
 * finance first verified a payment on the sale: the sale itself, or a plan's
 * first payment.
 */
public record ReadyPayoutDto(UUID transactionId, String transactionReference, UUID tenantId, String companyName,
                             String estateName, String plotLabel, BigDecimal amount, String currency,
                             BigDecimal amountOwed, String tranche, String trancheLabel, UUID sharePaymentId,
                             BigDecimal trancheAmount, BigDecimal heldInEscrow, Integer partCount, Integer partsPaid,
                             Integer nextPart, BigDecimal nextPartAmount, Instant verifiedAt,
                             PayoutAccount payoutAccount, List<String> blockers, PayoutDto lastAttempt) {

    /** Where the money would go. */
    public record PayoutAccount(String bankName, String accountLast4, String accountName) {
    }
}
