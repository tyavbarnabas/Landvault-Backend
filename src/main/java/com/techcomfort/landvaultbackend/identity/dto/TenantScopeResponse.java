package com.techcomfort.landvaultbackend.identity.dto;

import java.util.UUID;

/**
 * {@code GET /api/me/tenant-scope} — not a product endpoint (see
 * {@code MeController}), exists purely to make the tenant-context filter's
 * effect observable over HTTP: the {@code threadLocal*} fields are what
 * {@code TenantContextFilter} resolved and stored for this request; the
 * {@code db*} fields are what {@code TenantScopedDataSource} actually set
 * as Postgres session variables for this same request's transaction. In a
 * correctly working request the two should always agree — this response
 * exists specifically so that agreement (or a disagreement, if something's
 * broken) is directly visible without reaching for {@code psql}.
 */
public record TenantScopeResponse(
        UUID userId,
        UUID threadLocalTenantId,
        UUID threadLocalBranchId,
        boolean threadLocalPlatformStaff,
        String dbTenantId,
        String dbBranchId,
        String dbPlatformScope
) {
}
