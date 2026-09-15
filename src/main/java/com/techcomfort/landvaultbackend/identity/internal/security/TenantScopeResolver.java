package com.techcomfort.landvaultbackend.identity.internal.security;

import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure resolution logic behind {@link TenantContextFilter} — branch
 * resolution (Part 2) and the guarded branch switcher (Part 3), factored out
 * so both are testable without a servlet request or a database. See
 * AGENTS.md for the reasoning behind each branch below.
 */
final class TenantScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantScopeResolver.class);

    private TenantScopeResolver() {
    }

    /**
     * Resolves the scope implied by the token's claims alone — before any
     * branch-switch header is considered.
     */
    static TenantScope resolveBaseScope(AccessTokenClaims claims) {
        if (claims.platformStaff()) {
            // Platform staff never carry a tenant or branch, regardless of
            // whatever role claims happen to be present.
            return new TenantScope(claims.userId(), null, null, true);
        }

        var branchesPerAssignment = claims.roles().stream().map(RoleClaim::branch).toList();
        boolean hasOrgWideAssignment = branchesPerAssignment.stream().anyMatch(Objects::isNull);
        Set<UUID> distinctBranches = branchesPerAssignment.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        UUID branchId;
        if (distinctBranches.isEmpty()) {
            // No role assignment names a branch at all — including having
            // no role assignments whatsoever. Organization-/platform-wide.
            // Executive Directors, group finance officers.
            branchId = null;
        } else if (distinctBranches.size() == 1 && !hasOrgWideAssignment) {
            // Every single assignment names the same one branch, with no
            // org-wide assignment mixed in. A hard wall — a branch manager.
            // This is the only case that actually narrows the scope.
            branchId = distinctBranches.iterator().next();
        } else {
            // Either the assignments name different branches, or an
            // org-wide assignment is mixed in with a branch-scoped one (an
            // organization-wide finance role plus a branch-scoped sales
            // role, say — see AGENTS.md). Either way the user has
            // legitimate reach beyond a single branch, so this resolves to
            // organization-wide rather than arbitrarily picking one branch
            // or hiding the others. The switcher below lets them narrow
            // explicitly, on request.
            branchId = null;
        }

        return new TenantScope(claims.userId(), claims.tenantId(), branchId, false);
    }

    /**
     * Narrows {@code base} to one branch if an {@code X-Branch-Id} header is
     * present and every guard passes; otherwise returns {@code base}
     * unchanged. Never throws — a tampered or invalid header is silently
     * ignored (logged at debug) rather than surfaced as a 403, which would
     * leak whether a given branch id exists to whoever is probing it.
     */
    static TenantScope applyBranchSwitch(TenantScope base, String requestedBranchHeader, TenancyApi tenancyApi) {
        if (requestedBranchHeader == null || requestedBranchHeader.isBlank()) {
            return base;
        }

        // Guard 3: platform staff never uses this header to reach into a
        // tenant — they have no tenant of their own to switch within.
        if (base.platformStaff()) {
            log.debug("Ignoring X-Branch-Id for platform staff user {}", base.userId());
            return base;
        }
        // A user with no tenant (a buyer, an independent agent) has nothing
        // to switch within either — same reasoning as platform staff.
        if (base.tenantId() == null) {
            log.debug("Ignoring X-Branch-Id for user {} with no tenant", base.userId());
            return base;
        }
        // Guard 1: only an organization-wide scope may narrow. A
        // branch-scoped user (a hard wall, e.g. branch manager) cannot
        // widen or re-target via this header — resolveBaseScope already
        // decided their scope is fixed.
        if (base.branchId() != null) {
            log.debug("Ignoring X-Branch-Id for branch-scoped user {} (hard wall)", base.userId());
            return base;
        }

        UUID requestedBranchId;
        try {
            requestedBranchId = UUID.fromString(requestedBranchHeader);
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring malformed X-Branch-Id header for user {}", base.userId());
            return base;
        }

        // Guard 2: the requested branch must belong to the caller's own
        // tenant — the one check here that needs a database lookup.
        if (!tenancyApi.branchBelongsToTenant(requestedBranchId, base.tenantId())) {
            log.debug("Ignoring X-Branch-Id {} for user {}: not a branch of their own tenant",
                    requestedBranchId, base.userId());
            return base;
        }

        return new TenantScope(base.userId(), base.tenantId(), requestedBranchId, false);
    }
}
