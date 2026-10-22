package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.time.Instant;
import java.util.UUID;

/**
 * An instalment purchase whose plan is running (its deposit verified, not yet
 * paid off): what the reminder job and the developer's plan list need to name
 * it (phase 4).
 *
 * @param onPlanSince when the deposit was verified
 */
public record RunningPlan(
        UUID transactionId,
        String transactionReference,
        UUID sellerTenantId,
        UUID estateId,
        String estateName,
        UUID plotId,
        String plotLabel,
        UUID buyerUserId,
        String planName,
        Currency currency,
        Instant onPlanSince
) {
}
