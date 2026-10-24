package com.techcomfort.landvaultbackend.payments;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything a restructure letter states: the plan as it stood and the new
 * instalments proposed. Amounts in the purchase's currency.
 *
 * @param paidInstalments how many instalments are already paid (they stay paid)
 * @param balance         what was still owed, penalties aside — unchanged
 * @param carriedPenalty  penalties reached so far, kept and charged with the first new instalment
 * @param expiresOn       the last day (Lagos) the buyer may accept
 */
public record RestructureLetterRequest(
        UUID restructureId,
        UUID transactionId,
        UUID buyerUserId,
        UUID sellerTenantId,
        UUID estateId,
        UUID plotId,
        Instant requestedAt,
        String planName,
        int paidInstalments,
        BigDecimal planTotal,
        BigDecimal balance,
        BigDecimal carriedPenalty,
        String frequency,
        List<PlanSchedule.Instalment> newInstalments,
        LocalDate expiresOn,
        String currency
) {
}
