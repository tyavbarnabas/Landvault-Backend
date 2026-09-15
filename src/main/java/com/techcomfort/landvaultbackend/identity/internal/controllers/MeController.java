package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.MeResponse;
import com.techcomfort.landvaultbackend.identity.dto.TenantScopeResponse;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenClaims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Not part of the task's named deliverables — a minimal, deliberately
 * outside-{@code /api/auth/**} set of endpoints so each slice's own
 * verification steps (call a protected endpoint; confirm 401/403; prove
 * {@code @PreAuthorize} actually works; prove the tenant-context filter and
 * its {@code SET LOCAL} mechanism actually took effect) have something real
 * to call against. {@code /api/auth/**} itself is entirely public, so it
 * can't serve that purpose.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    @PersistenceContext
    private EntityManager entityManager;

    @GetMapping
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AccessTokenClaims claims) {
        return ResponseEntity.ok(new MeResponse(
                claims.userId(), claims.email(), claims.tenantId(), claims.platformStaff(), claims.permissions()));
    }

    // Exists only to prove @EnableMethodSecurity + @PreAuthorize work end
    // to end — a buyer's token has no admin.* permissions, so this 403s.
    @GetMapping("/admin-check")
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<Void> adminCheck() {
        return ResponseEntity.ok().build();
    }

    /**
     * Verification-only, same spirit as {@link #adminCheck()} above: reads
     * back both what {@code TenantContextFilter} resolved into the
     * ThreadLocal for this request, and — by querying inside a real
     * transaction — what {@code TenantScopedDataSource} actually set as
     * Postgres session variables for that same request.
     * <p>
     * {@code @Transactional} is load-bearing here, not decorative: without
     * it, each query below would run in its own implicit, autocommitting
     * statement, and {@code SET LOCAL}'s effect would already be gone
     * before the next one ran — {@code SET LOCAL} only lasts for the
     * current transaction, and autocommit makes every unbound statement its
     * own transaction. Reading back through the same {@link EntityManager}
     * every repository in this codebase already uses (rather than a
     * separately-acquired {@code JdbcTemplate} connection) is what
     * guarantees these three queries share the exact connection/transaction
     * {@code TenantScopedDataSource} set the variables on in the first
     * place — this was tried with {@code JdbcTemplate} first and every
     * value silently came back {@code NULL} for exactly this reason.
     */
    @GetMapping("/tenant-scope")
    @Transactional(readOnly = true)
    public ResponseEntity<TenantScopeResponse> tenantScope() {
        TenantScope scope = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException(
                        "No TenantContext for an authenticated request — TenantContextFilter should have set one."));

        String dbTenantId = currentSetting("landvault.tenant_id");
        String dbBranchId = currentSetting("landvault.branch_id");
        String dbPlatformScope = currentSetting("landvault.platform_scope");

        return ResponseEntity.ok(new TenantScopeResponse(
                scope.userId(), scope.tenantId(), scope.branchId(), scope.platformStaff(),
                dbTenantId, dbBranchId, dbPlatformScope));
    }

    private String currentSetting(String settingName) {
        return (String) entityManager
                .createNativeQuery("SELECT current_setting('" + settingName + "', true)")
                .getSingleResult();
    }
}
