package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.dto.AssignableRoleDto;
import com.techcomfort.landvaultbackend.identity.internal.domain.Permission;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.RolePermission;
import com.techcomfort.landvaultbackend.identity.internal.enums.RoleScope;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.InvitationException;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RolePermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final RolePermissionRepository rolePermissionRepository;
    private final TenancyApi tenancyApi;

    /** Every role a tenant may assign (scope set), with whether the caller could grant it. */
    @Transactional(readOnly = true)
    public List<AssignableRoleDto> assignableRoles() {
        List<Role> roles = roleRepository.findAll().stream()
                .filter(r -> r.getScope() != null)
                .sorted(Comparator.comparing(Role::getScope).thenComparing(Role::getName))
                .toList();
        Map<UUID, String> permissionCodes = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(Permission::getId, Permission::getCode));
        Map<UUID, List<String>> carried = rolePermissionRepository
                .findByRoleIdIn(roles.stream().map(Role::getId).toList()).stream()
                .collect(Collectors.groupingBy(RolePermission::getRoleId,
                        Collectors.mapping(rp -> permissionCodes.get(rp.getPermissionId()), Collectors.toList())));
        Set<String> held = heldAuthorities();
        return roles.stream().map(r -> {
            List<String> permissions = carried.getOrDefault(r.getId(), List.of()).stream().sorted().toList();
            return new AssignableRoleDto(r.getCode(), r.getName(), r.getDescription(),
                    r.getScope().name().toLowerCase(Locale.ROOT), permissions, held.containsAll(permissions),
                    !Boolean.TRUE.equals(r.getSystemRole()));
        }).toList();
    }

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
