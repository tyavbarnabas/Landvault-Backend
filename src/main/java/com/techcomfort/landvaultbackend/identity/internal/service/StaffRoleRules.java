package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.enums.RoleScope;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.InvitationException;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The rules for giving a tenant role to someone, shared by invitations and by
 * role changes so the two can never disagree: the role must be one a tenant
 * may assign, its scope decides the branch, and the caller must already hold
 * every permission it carries (SI-4). See AGENTS.md, "Tenant self-service".
 */
@Component
@RequiredArgsConstructor
public class StaffRoleRules {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final TenancyApi tenancyApi;

    public Role assignableRole(String roleCode) {
        return roleRepository.findByCode(roleCode.trim())
                .filter(r -> r.getScope() != null)
                .orElseThrow(() -> new InvitationException.RoleNotInvitable(roleCode));
    }

    /** Required, forbidden or optional by the role's scope — and always the company's own branch. Returns its name. */
    public String branchFor(Role role, UUID branchId, UUID tenantId) {
        if (role.getScope() == RoleScope.COMPANY && branchId != null) {
            throw new InvitationException.ScopeMismatch(
                    "'" + role.getCode() + "' is company-wide and can't be limited to a branch. Leave branchId out.");
        }
        if (role.getScope() == RoleScope.BRANCH && branchId == null) {
            throw new InvitationException.ScopeMismatch(
                    "'" + role.getCode() + "' runs a branch, so branchId is required.");
        }
        if (branchId == null) {
            return null;
        }
        return tenancyApi.branchNameFor(branchId, tenantId).orElseThrow(InvitationException.BranchNotFound::new);
    }

    /** SI-4: the caller must already hold every permission the role carries. */
    public void requireCallerHolds(Role role) {
        if (!callerHoldsAllOf(List.of(role.getId()))) {
            throw new InvitationException.CannotGrant(role.getCode());
        }
    }

    public boolean callerHoldsAllOf(Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return true;
        }
        return heldAuthorities().containsAll(permissionRepository.findCodesByRoleIdIn(roleIds));
    }

    public static Set<String> heldAuthorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
