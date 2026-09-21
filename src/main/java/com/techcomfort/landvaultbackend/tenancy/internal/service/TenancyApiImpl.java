package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Both methods here answer questions asked <strong>before any tenant scope
 * exists</strong>, so neither can read its table through an ordinary
 * repository: RLS would fail closed and return nothing.
 * <ul>
 *   <li>{@code isTenantActive} runs inside {@code AuthService.login()} /
 *   {@code refresh()} — login is what <em>establishes</em> a scope, so it
 *   cannot presuppose one.</li>
 *   <li>{@code branchBelongsToTenant} runs inside {@code TenantContextFilter}
 *   while it is still resolving the scope, so {@code TenantContext} is not
 *   populated yet.</li>
 * </ul>
 * Both therefore go through {@code SECURITY DEFINER} functions (changeset
 * 043), which run as the table owner and so are not subject to RLS. Each
 * returns a boolean and never a row, so they answer exactly the question the
 * caller is entitled to ask without reopening either table to unscoped reads.
 * <p>
 * <strong>Do not "simplify" either of these back to a repository call.</strong>
 * It compiles, it passes every superuser-connected integration test, and it
 * breaks every tenant-staff login in any environment where RLS is actually
 * enforced. See AGENTS.md.
 */
@Service
public class TenancyApiImpl implements TenancyApi {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public boolean branchBelongsToTenant(UUID branchId, UUID tenantId) {
        if (branchId == null || tenantId == null) {
            return false;
        }
        Object result = entityManager
                .createNativeQuery("SELECT landvault_branch_belongs_to_tenant(:branchId, :tenantId)")
                .setParameter("branchId", branchId)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTenantActive(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        Object result = entityManager
                .createNativeQuery("SELECT landvault_tenant_is_active(:tenantId)")
                .setParameter("tenantId", tenantId)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
