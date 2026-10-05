package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;

import java.util.Objects;

/**
 * EB-2: a company-level estate (no branch) is visible to branch-scoped staff
 * but not writable by them. <strong>The database will not enforce this</strong>:
 * changeset 044's branch policy has a {@code branch_id IS NULL} clause in
 * both {@code USING} and {@code WITH CHECK}, so the same rule that lets a
 * Double King manager see shared inventory would let them change it. Every
 * inventory write path calls this before writing. See AGENTS.md.
 */
final class EstateWriteAccess {

    private EstateWriteAccess() {
    }

    static void requireWritable(Estate estate) {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        if (scope.branchId() != null && !Objects.equals(scope.branchId(), estate.getBranchId())) {
            throw new InventoryException.EstateReadOnlyForBranch();
        }
    }
}
