package com.techcomfort.landvaultbackend.checkout;

import java.util.UUID;

/**
 * Phase 5b: the developer revoked an instalment plan and the plot went back on
 * sale. Published inside the revoking transaction and consumed by plain
 * synchronous listeners (the portfolio's record) — the {@link PlanWithdrawn}
 * shape.
 */
public record PlanRevoked(UUID transactionId, UUID buyerUserId, UUID plotId, UUID sellerTenantId) {
}
