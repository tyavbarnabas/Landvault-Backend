package com.techcomfort.landvaultbackend.identity.internal.security;

import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch resolution (Part 2) and the guarded branch switcher (Part 3), in
 * isolation from the servlet layer and the database — see AGENTS.md for the
 * reasoning behind each case.
 */
@ExtendWith(MockitoExtension.class)
class TenantScopeResolverTest {

    @Mock
    private TenancyApi tenancyApi;

    private static AccessTokenClaims claims(UUID userId, UUID tenantId, boolean platformStaff, List<RoleClaim> roles) {
        return new AccessTokenClaims(userId, "user@example.com", tenantId, platformStaff, List.of(), roles);
    }

    // --- resolveBaseScope ---

    @Test
    void noRoleAssignmentsResolveToOrganizationWide() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        TenantScope scope = TenantScopeResolver.resolveBaseScope(claims(userId, tenantId, false, List.of()));

        assertThat(scope.userId()).isEqualTo(userId);
        assertThat(scope.tenantId()).isEqualTo(tenantId);
        assertThat(scope.branchId()).isNull();
        assertThat(scope.platformStaff()).isFalse();
    }

    @Test
    void noBranchOnAnyAssignmentResolvesToOrganizationWide() {
        UUID tenantId = UUID.randomUUID();
        List<RoleClaim> roles = List.of(new RoleClaim("executive_director", null), new RoleClaim("finance_officer", null));

        TenantScope scope = TenantScopeResolver.resolveBaseScope(claims(UUID.randomUUID(), tenantId, false, roles));

        assertThat(scope.branchId()).isNull();
    }

    @Test
    void everyAssignmentNamingTheSameBranchIsAHardWall() {
        UUID branchId = UUID.randomUUID();
        List<RoleClaim> roles = List.of(new RoleClaim("branch_manager", branchId));

        TenantScope scope = TenantScopeResolver.resolveBaseScope(claims(UUID.randomUUID(), UUID.randomUUID(), false, roles));

        assertThat(scope.branchId()).isEqualTo(branchId);
    }

    @Test
    void assignmentsNamingDifferentBranchesResolveToOrganizationWide() {
        List<RoleClaim> roles = List.of(
                new RoleClaim("sales_manager", UUID.randomUUID()),
                new RoleClaim("sales_manager", UUID.randomUUID()));

        TenantScope scope = TenantScopeResolver.resolveBaseScope(claims(UUID.randomUUID(), UUID.randomUUID(), false, roles));

        assertThat(scope.branchId()).isNull();
    }

    @Test
    void anOrgWideAssignmentMixedWithABranchScopedOneResolvesToOrganizationWide() {
        // A real AGENTS.md scenario: an organization-wide finance role plus
        // a branch-scoped sales role for the same person. Narrowing to the
        // branch would hide data the finance role entitles them to.
        List<RoleClaim> roles = List.of(
                new RoleClaim("finance_officer", null),
                new RoleClaim("sales_manager", UUID.randomUUID()));

        TenantScope scope = TenantScopeResolver.resolveBaseScope(claims(UUID.randomUUID(), UUID.randomUUID(), false, roles));

        assertThat(scope.branchId()).isNull();
    }

    @Test
    void platformStaffAlwaysResolvesToNullTenantAndBranch() {
        List<RoleClaim> roles = List.of(new RoleClaim("super_admin", null));

        TenantScope scope = TenantScopeResolver.resolveBaseScope(claims(UUID.randomUUID(), null, true, roles));

        assertThat(scope.tenantId()).isNull();
        assertThat(scope.branchId()).isNull();
        assertThat(scope.platformStaff()).isTrue();
    }

    // --- applyBranchSwitch ---

    @Test
    void switcherNarrowsAnOrganizationWideScopeToABranchOfItsOwnTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        TenantScope orgWide = new TenantScope(UUID.randomUUID(), tenantId, null, false);
        when(tenancyApi.branchBelongsToTenant(branchId, tenantId)).thenReturn(true);

        TenantScope narrowed = TenantScopeResolver.applyBranchSwitch(orgWide, branchId.toString(), tenancyApi);

        assertThat(narrowed.branchId()).isEqualTo(branchId);
        assertThat(narrowed.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void switcherRejectsABranchFromAnotherTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantsBranch = UUID.randomUUID();
        TenantScope orgWide = new TenantScope(UUID.randomUUID(), tenantId, null, false);
        when(tenancyApi.branchBelongsToTenant(otherTenantsBranch, tenantId)).thenReturn(false);

        TenantScope result = TenantScopeResolver.applyBranchSwitch(orgWide, otherTenantsBranch.toString(), tenancyApi);

        assertThat(result).isEqualTo(orgWide);
        assertThat(result.branchId()).isNull();
    }

    @Test
    void switcherIgnoresTheHeaderForABranchScopedUser() {
        UUID tenantId = UUID.randomUUID();
        UUID hardWallBranch = UUID.randomUUID();
        UUID differentBranch = UUID.randomUUID();
        TenantScope branchScoped = new TenantScope(UUID.randomUUID(), tenantId, hardWallBranch, false);

        TenantScope result = TenantScopeResolver.applyBranchSwitch(branchScoped, differentBranch.toString(), tenancyApi);

        assertThat(result).isEqualTo(branchScoped);
        verify(tenancyApi, never()).branchBelongsToTenant(any(), any());
    }

    @Test
    void switcherIgnoresTheHeaderForPlatformStaff() {
        TenantScope platformScope = new TenantScope(UUID.randomUUID(), null, null, true);

        TenantScope result = TenantScopeResolver.applyBranchSwitch(platformScope, UUID.randomUUID().toString(), tenancyApi);

        assertThat(result).isEqualTo(platformScope);
        verify(tenancyApi, never()).branchBelongsToTenant(any(), any());
    }

    @Test
    void switcherIgnoresAMalformedHeaderRatherThanThrowing() {
        TenantScope orgWide = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), null, false);

        TenantScope result = TenantScopeResolver.applyBranchSwitch(orgWide, "not-a-uuid", tenancyApi);

        assertThat(result).isEqualTo(orgWide);
    }

    @Test
    void switcherIgnoresAMissingHeader() {
        TenantScope orgWide = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), null, false);

        TenantScope result = TenantScopeResolver.applyBranchSwitch(orgWide, null, tenancyApi);

        assertThat(result).isEqualTo(orgWide);
    }
}
