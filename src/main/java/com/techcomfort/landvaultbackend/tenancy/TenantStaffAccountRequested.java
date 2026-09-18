package com.techcomfort.landvaultbackend.tenancy;

import java.util.UUID;

/**
 * Published when a tenant needs a staff account created — today, only the
 * Executive Director account {@code POST /api/admin/tenants} creates
 * alongside the organization. See AGENTS.md for why this is a Spring
 * application event rather than a direct call into {@code identity}: the
 * {@code User}/{@code Role}/{@code UserRole} entities this needs live in
 * {@code identity.internal}, reachable only through {@code identity}'s own
 * public API — but {@code identity} already depends on {@code tenancy}
 * (via {@code TenancyApi}, for the tenant-context filter's branch
 * switcher). A direct {@code tenancy} → {@code identity} call would close
 * that into a module dependency cycle, which {@code ModularityTests}
 * rejects. Publishing this event instead means {@code tenancy} never
 * imports anything from {@code identity} at all — only {@code identity}
 * imports this event type, which is exactly the same direction its
 * existing {@code TenancyApi} dependency already goes.
 * <p>
 * Deliberately handled by a plain, synchronous {@code @EventListener} in
 * {@code identity} (see {@code TenantStaffAccountListener}), not Spring
 * Modulith's {@code @ApplicationModuleListener} — the latter defaults to
 * asynchronous, after-commit execution, which would break the "user
 * creation and role assignment roll back together with the organization"
 * requirement this event exists to satisfy. A plain listener runs
 * synchronously, in the same thread and the same transaction as
 * {@code publishEvent(...)} — an exception here fails the whole call, the
 * same as if it had been a direct method call.
 */
public record TenantStaffAccountRequested(
        UUID tenantId,
        String roleCode,
        String firstName,
        String lastName,
        String email,
        String phone
) {
}
