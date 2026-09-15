package com.techcomfort.landvaultbackend.common;

import java.util.UUID;

/**
 * The resolved tenant/branch scope of one authenticated request — what
 * {@link TenantContext} carries. Immutable: a scope is established once, by
 * the tenant-context filter, and never mutated mid-request. See AGENTS.md
 * for how {@code tenantId}/{@code branchId} get resolved.
 *
 * @param userId       the authenticated user
 * @param tenantId     null for buyers, platform staff, and independent agents
 * @param branchId     null = organization-/platform-wide
 * @param platformStaff true for Super Admin / platform moderator / compliance officer
 */
public record TenantScope(
        UUID userId,
        UUID tenantId,
        UUID branchId,
        boolean platformStaff
) {
}
