/**
 * Owns tenant (land-developer company) lifecycle: onboarding, the
 * verification state machine and its audit trail, branches, plan and
 * entitlements, and the platform-wide audit log of tenant-affecting
 * decisions.
 * <p>
 * Does not own user accounts — see the {@code identity} module. A tenant's
 * staff are users linked by {@code tenantId}; this module owns what that
 * tenant is, not who works for it.
 * <p>
 * Public surface: {@link com.techcomfort.landvaultbackend.tenancy.TenancyApi}
 * (currently just {@code branchBelongsToTenant}, used by identity's tenant
 * context filter) and the DTOs in {@code tenancy.dto}, which the two
 * {@code /api/admin/tenants} read endpoints return — see
 * {@code tenancy.internal.controllers.AdminTenantController}. Every write
 * operation (onboarding, verification decisions, plan/status changes,
 * support access) is a later slice; only reads exist today.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tenancy"
)
package com.techcomfort.landvaultbackend.tenancy;
