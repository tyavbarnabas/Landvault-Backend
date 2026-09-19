package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code PUT /api/admin/tenants/{id}/plan} body — flat entitlement booleans,
 * per this slice's task spec. The real frontend's {@code updateTenantPlan}
 * sends a nested {@code { plan, entitlements: {...} } } shape instead
 * (reusing {@link TenantEntitlementsDto}) — a deliberate divergence from the
 * task spec, not an oversight; see AGENTS.md. Independent of both
 * {@code TenantStatus} and {@code VerificationState} — callable regardless
 * of either.
 */
public record TenantPlanUpdateRequest(
        @NotBlank String plan,
        boolean marketplacePublishing,
        boolean mlmModule,
        boolean fxRails
) {
}
