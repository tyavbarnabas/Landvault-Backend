package com.techcomfort.landvaultbackend.identity.internal.security;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns an authenticated request into a scoped database session — the most
 * security-critical filter in the backend, since every tenant/branch
 * isolation guarantee downstream depends on it being right. Runs after
 * {@link JwtAuthenticationFilter} (there must be an authenticated principal
 * to read) and before the controller. See AGENTS.md for the branch
 * resolution rules and for why {@code TenantScopedDataSource} (in
 * {@code common}), not this filter, is what actually sets the Postgres
 * session variables — this filter has no database connection yet.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    static final String BRANCH_SWITCH_HEADER = "X-Branch-Id";

    private final TenancyApi tenancyApi;

    public TenantContextFilter(TenancyApi tenancyApi) {
        this.tenancyApi = tenancyApi;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            // Deliberately reads the tenant/branch ONLY from the
            // authenticated principal JwtAuthenticationFilter already
            // validated — never from a request header or parameter.
            // X-Tenant-ID (a client-supplied header naming the tenant
            // directly) is the common tutorial pattern and looks
            // reasonable, but the token is signed and a header is not:
            // honouring a client-supplied tenant id would be a
            // cross-tenant data breach on the very first request that
            // tried it. Only X-Branch-Id is ever consulted (below), and
            // only to NARROW an already-resolved, already-authorized scope
            // — never to name a tenant.
            if (authentication != null && authentication.getPrincipal() instanceof AccessTokenClaims claims) {
                TenantScope scope = TenantScopeResolver.resolveBaseScope(claims);
                scope = TenantScopeResolver.applyBranchSwitch(scope, request.getHeader(BRANCH_SWITCH_HEADER), tenancyApi);
                TenantContext.set(scope);
            }
            // An unauthenticated request (/api/auth/**, /actuator/health,
            // dev-only docs) simply sets nothing and continues —
            // TenantScopedDataSource treats an empty TenantContext as "no
            // scope, do nothing," not as an error condition.

            chain.doFilter(request, response);
        } finally {
            // Unconditional, including every exception path thrown by the
            // rest of the chain or the controller — see TenantContext's own
            // Javadoc for why this can never be skipped: Tomcat pools
            // threads, and a pooled thread that keeps one tenant's scope
            // past the end of its request silently hands that scope to
            // whichever unrelated tenant's request lands on that same
            // thread next.
            TenantContext.clear();
        }
    }
}
