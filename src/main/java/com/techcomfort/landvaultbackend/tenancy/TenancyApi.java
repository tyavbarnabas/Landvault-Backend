package com.techcomfort.landvaultbackend.tenancy;

import java.util.UUID;

/**
 * The tenancy module's only public surface. Add methods here as other
 * modules genuinely need a cross-module read; never widen access by
 * exposing {@code tenancy.internal} types (entities, repositories)
 * directly. See AGENTS.md.
 */
public interface TenancyApi {

    /**
     * Used only by identity's branch switcher (see
     * {@code TenantContextFilter}/AGENTS.md) to check a client-supplied
     * {@code X-Branch-Id} header against the requesting user's own tenant
     * before honouring it — never to look up branch details, which stays
     * out of this API on purpose.
     */
    boolean branchBelongsToTenant(UUID branchId, UUID tenantId);
}
