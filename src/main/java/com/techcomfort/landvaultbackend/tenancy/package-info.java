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
 * Only enums exist here so far (see {@code tenancy.internal.domain}) —
 * there is no {@code Tenant} entity yet, so {@link com.techcomfort.landvaultbackend.tenancy.TenancyApi}
 * is currently empty. It still exists now so the module boundary is in
 * place before the first entity lands.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tenancy"
)
package com.techcomfort.landvaultbackend.tenancy;
