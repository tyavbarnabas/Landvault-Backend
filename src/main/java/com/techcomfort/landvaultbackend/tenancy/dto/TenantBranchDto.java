package com.techcomfort.landvaultbackend.tenancy.dto;

import java.util.UUID;

/**
 * {@code managerName} resolves {@code Branch.managerUserId} via
 * {@code IdentityApi} — null if no manager is assigned yet (the common
 * case; no branch-management flow exists to set one). {@code estateCount}
 * is always 0 — no inventory module exists yet to source a real count
 * from; see AGENTS.md's "no fabricated data" rule.
 */
public record TenantBranchDto(UUID id, String name, String managerName, long estateCount) {
}
