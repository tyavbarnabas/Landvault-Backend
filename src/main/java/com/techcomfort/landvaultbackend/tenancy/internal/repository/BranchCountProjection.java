package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import java.util.UUID;

/** JPQL constructor-expression target for {@link BranchRepository#countGroupedByOrganizationId}. */
public record BranchCountProjection(
        UUID organizationId,
        Long branchCount) {
}
