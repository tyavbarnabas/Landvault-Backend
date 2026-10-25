package com.techcomfort.landvaultbackend.checkout;

import java.math.BigDecimal;

/**
 * What is coming back to the buyer on a purchase, across every payment owed
 * back on it — a withdrawn or revoked plan is refunded payment by payment, in
 * parts (see AGENTS.md, "Phase 5a").
 *
 * @param amount           everything owed back so far; it can grow if finance later rejects a payment that was
 *                         waiting for them when the plan ended
 * @param refunded         what Paystack reports as processed
 * @param status           {@code none} (nothing is owed back), {@code pending} (nothing processed yet),
 *                         {@code partly_refunded} or {@code refunded} (all of {@code amount} processed)
 * @param needsYourAccount a bank-transfer payment can't be returned until the buyer gives an account
 */
public record PlanRefund(BigDecimal amount, BigDecimal refunded, String status, boolean needsYourAccount) {
}
