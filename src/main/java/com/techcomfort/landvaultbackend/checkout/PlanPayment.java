package com.techcomfort.landvaultbackend.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One succeeded payment on a purchase, as the portfolio shows it (3b): what it
 * was for, and whether finance has verified it.
 *
 * @param purpose            outright, deposit, instalment or balance
 * @param instalmentSequence which instalment, for an instalment payment
 * @param financeVerifiedAt  null while finance hasn't verified it
 */
public record PlanPayment(String reference, String purpose, Integer instalmentSequence, BigDecimal amount,
                          Instant paidAt, Instant financeVerifiedAt, UUID receiptId) {

    public boolean verified() {
        return financeVerifiedAt != null;
    }
}
