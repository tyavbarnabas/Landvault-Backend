package com.techcomfort.landvaultbackend.tenancy;

import java.util.Collection;
import java.util.Map;
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

    /**
     * Used by {@code AuthService.login()}/{@code .refresh()} (tenancy slice
     * B2) to refuse issuing a new access token to a suspended/offboarded
     * tenant's staff — see AGENTS.md's "closing the session-revocation gap"
     * note. Returns {@code false} (not an internal {@code TenantStatus}
     * value — a DTO/API boundary must not leak an internal type) for an
     * unknown {@code tenantId} too, failing closed the same way RLS does
     * with no context established.
     */
    boolean isTenantActive(UUID tenantId);

    /**
     * Display names by tenant id, for surfaces that legitimately name a
     * company — today only the Super Admin conflict queue (CD-6).
     * <p>
     * Deliberately an ordinary repository read, <strong>not</strong> a
     * {@code SECURITY DEFINER} function like the two methods above. That
     * means RLS still applies: a platform-scope caller sees every company,
     * and a tenant-scoped caller sees only their own. For this method that
     * is exactly the behaviour wanted — if it is ever called from a
     * tenant-facing path by mistake, the database refuses to name the other
     * company rather than the code having to remember not to ask. Defence
     * in depth behind CD-11's non-disclosure rule.
     * <p>
     * Batched: one page of conflicts resolves its companies in one query.
     * Ids with no match are absent rather than an error.
     */
    Map<UUID, String> organizationNamesFor(Collection<UUID> tenantIds);
}
