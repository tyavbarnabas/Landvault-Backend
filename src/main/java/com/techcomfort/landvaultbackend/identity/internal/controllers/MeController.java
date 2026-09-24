package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.MeResponse;
import com.techcomfort.landvaultbackend.identity.dto.TenantScopeResponse;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenClaims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = OpenApiConfig.TAG_SESSION)
public class MeController {

    @PersistenceContext
    private EntityManager entityManager;

    @Operation(
            summary = "The current token's user",
            description = "Who the bearer token belongs to, with the flattened permission slugs it "
                    + "carries. Permissions are assembled at login from the user's roles and are "
                    + "never stored on the user row.")
    @ApiResponse(responseCode = "200", description = "The authenticated user")
    @GetMapping
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AccessTokenClaims claims) {
        return ResponseEntity.ok(new MeResponse(
                claims.userId(), claims.email(), claims.tenantId(), claims.platformStaff(), claims.permissions()));
    }

    // Exists only to prove @EnableMethodSecurity + @PreAuthorize work end
    // to end — a buyer's token has no admin.* permissions, so this 403s.
    @Operation(
            summary = "Diagnostic: prove permission checks are wired",
            description = "**A probe, not a feature.** It exists so that method-level security can "
                    + "be verified end to end against a real token. Returns 204 for a caller holding "
                    + "`admin.tenants.view` and 403 otherwise; it does nothing else and has no "
                    + "response body.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Caller holds `admin.tenants.view`"),
            @ApiResponse(responseCode = "403", description = "Caller does not", content = @Content())
    })
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
    @Operation(
            summary = "Diagnostic: the tenant scope reaching the database",
            description = """
                    **A probe, not a feature.** It reports the scope resolved from the token \
                    alongside the Postgres session variables actually set on the connection, which \
                    is how the two are verified to agree.

                    Isolation is enforced by row-level security in the database, not by query \
                    filters, and these session variables are what the policies read. Scope comes \
                    from the signed token, never from a request header — an `X-Branch-Id` header \
                    can only ever *narrow* an organization-wide scope to a branch of the caller's \
                    own tenant, and is silently ignored otherwise.""")
    @ApiResponse(responseCode = "200", description = "Resolved scope and the matching session variables")
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
