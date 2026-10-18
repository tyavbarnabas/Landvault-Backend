package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;

import java.util.UUID;

/** The caller's company, for the developer-facing document routes. */
final class PortalScopes {

    private PortalScopes() {
    }

    static TenantScope current() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    /** The caller's company; refused for anyone without one (buyers, platform staff). */
    static UUID company(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new DocumentException.CompanyScopeRequired();
        }
        return scope.tenantId();
    }

    /** As {@link #company}, and never from inside one branch — including a director narrowed with {@code X-Branch-Id}. */
    static UUID companyWide(TenantScope scope) {
        UUID tenantId = company(scope);
        if (scope.branchId() != null) {
            throw new DocumentException.CompanyWideOnly();
        }
        return tenantId;
    }
}
