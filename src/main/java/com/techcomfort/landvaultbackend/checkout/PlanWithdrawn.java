package com.techcomfort.landvaultbackend.checkout;

import java.util.UUID;

/**
 * Phase 5a: a buyer withdrew from their instalment plan and the plot went back
 * on sale. Published inside the withdrawing transaction and consumed by plain
 * synchronous listeners (the portfolio's record), so they commit or roll back
 * together — the {@link PlotAllocated} shape.
 */
public record PlanWithdrawn(UUID transactionId, UUID buyerUserId, UUID plotId, UUID sellerTenantId) {
}
