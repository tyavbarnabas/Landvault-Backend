package com.techcomfort.landvaultbackend.identity.internal.enums;

/**
 * How a tenant may assign a role (changeset 067) — data about the role, not
 * logic. A role with no scope ({@code null}) is not assignable by a tenant at
 * all: platform roles, {@code buyer}, {@code broker}.
 */
public enum RoleScope {
    /** Organisation-wide only — e.g. executive_director. */
    COMPANY,
    /** Must name a branch — e.g. branch_manager. */
    BRANCH,
    /** Either — sales, surveyor, finance, legal. */
    EITHER
}
