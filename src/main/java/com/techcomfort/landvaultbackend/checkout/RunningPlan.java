package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;

import java.time.Instant;
import java.util.UUID;

/**
 * An instalment purchase whose plan is running (its deposit verified, not yet
 * paid off): what the reminder job and the developer's plan list need to name
 * it (phase 4).
 *
 * @param onPlanSince when the purchase last changed ({@code updated_at}) — not when the plan started: a
 *                    restructure, a notice or any other update moves it. The deposit's own verification is
 *                    the payments module's.
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
