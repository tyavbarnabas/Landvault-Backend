package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import com.techcomfort.landvaultbackend.common.PostalAddress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Finance verified a sale: the plot is now the buyer's. Published by checkout
 * inside the allocating transaction and consumed by a plain synchronous
 * listener in the portfolio module, so the owned-plot record and the sale
 * commit or roll back together — the same shape as
 * {@code TenantStaffAccountRequested} (AGENTS.md, "Cross-module writes that
 * would create a cycle"). Never handle it asynchronously.
 *
 * @param plan        the wire value: outright, installment or milestone
 * @param reservedAt  when the buyer reserved the plot — the letter's "application" date
 * @param actorUserId the finance officer who allocated it
 * @param buyerAddress the address copied onto the purchase when it was opened; null before 097
 * @param provisional  an instalment plan's deposit was verified: the plot is held on the plan, not
 *                     sold — the provisional letter and an active plan, not ownership
 */
public record PlotAllocated(
        UUID transactionId,
        UUID buyerUserId,
        UUID plotId,
        UUID estateId,
        UUID sellerTenantId,
        String plan,
        PlotIntent intent,
        BigDecimal basePrice,
        BigDecimal cornerPremiumPct,
        BigDecimal totalPrice,
        Currency currency,
        Instant reservedAt,
        UUID actorUserId,
        PostalAddress buyerAddress,
        boolean provisional
) {
}
