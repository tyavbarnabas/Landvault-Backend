package com.techcomfort.landvaultbackend.tenancy.dto;

import java.util.List;
import java.util.UUID;

/**
 * One directory row. Deliberately NOT the frontend's full {@code Tenant}
 * shape — {@code branchCount} (a projection) instead of a real
 * {@code branches[]} array, to avoid loading every organization's branches
 * for every row of a page. See AGENTS.md for why this and the frontend's
 * current {@code tenantsService.ts} contract (which types the directory's
 * response as {@code Page<Tenant>}, the full shape) don't match today, and
 * what would need to change on either side to reconcile them.
 * <p>
 * {@code plan}/{@code verificationState}/{@code status} are the enums'
 * lowercase wire values (not the internal enum types themselves) — a
 * public DTO must not expose an internal type through its own signature,
 * see AGENTS.md.
 * <p>
 * {@code primaryContactName} is always {@code null} today —
 * {@code primaryContactEmail} is {@link com.techcomfort.landvaultbackend.tenancy.internal.domain.Organization#getCompanyEmail()},
 * not a real person's contact — see AGENTS.md's note on this slice's
 * findings: nothing in the schema stores an actual primary contact person
 * (name, role, personal government ID) at all.
 */
public record TenantSummaryDto(
        UUID id,
        String displayName,
        String primaryContactName,
        String primaryContactEmail,
        String plan,
        String verificationState,
        String status,
        List<String> statesOfOperation,
        long branchCount,
        String createdDate
) {
}
