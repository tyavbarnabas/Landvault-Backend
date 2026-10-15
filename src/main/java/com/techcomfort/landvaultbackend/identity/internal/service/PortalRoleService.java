package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.AssignableRoleDto;
import com.techcomfort.landvaultbackend.identity.dto.CreateRoleRequest;
import com.techcomfort.landvaultbackend.identity.dto.PortalPermissionDto;
import com.techcomfort.landvaultbackend.identity.dto.UpdateRoleRequest;
import com.techcomfort.landvaultbackend.identity.internal.domain.Permission;
import com.techcomfort.landvaultbackend.identity.internal.domain.PermissionGroup;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.RolePermission;
import com.techcomfort.landvaultbackend.identity.internal.enums.RoleScope;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.RoleException;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionGroupRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RolePermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.StaffInvitationRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Company-made roles: an Executive Director bundles {@code portal.*}
 * permissions they hold into a role of their own. Permissions stay seeded;
 * only the bundles are the company's. See AGENTS.md, "Company-made roles".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalRoleService {

    static final String PORTAL_PREFIX = "portal.";
    static final int MAX_CUSTOM_ROLES = 30;

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final StaffInvitationRepository invitationRepository;
    private final StaffRoleRules rules;
    private final AuditApi auditApi;

    /**
     * The permissions a company role may carry, and which of them the caller holds, ordered by group then
     * within it. The database requires every portal permission to have a group (changeset 085).
     */
    @Transactional(readOnly = true)
    public List<PortalPermissionDto> permissions() {
        Set<String> held = StaffRoleRules.heldAuthorities();
        Map<String, PermissionGroup> groups = permissionGroupRepository.findAll().stream()
                .collect(Collectors.toMap(PermissionGroup::getCode, Function.identity()));
        return permissionRepository.findByCodeStartingWithOrderByCodeAsc(PORTAL_PREFIX).stream()
                .sorted(Comparator.<Permission>comparingInt(p -> groups.get(p.getGroupCode()).getSortOrder())
                        .thenComparing(Permission::getSortOrder))
                .map(p -> new PortalPermissionDto(p.getCode(), p.getLabel(), p.getDescription(),
                        Boolean.TRUE.equals(p.getRequiresCompanyWide()), held.contains(p.getCode()),
                        p.getGroupCode(), groups.get(p.getGroupCode()).getLabel()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssignableRoleDto> roles() {
        return rules.assignableRoles(currentScope().tenantId());
    }

    @Transactional
    public AssignableRoleDto create(CreateRoleRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        String name = request.name().trim();
        RoleScope roleScope = parseScope(request.scope());

        if (roleRepository.countByTenantId(tenantId) >= MAX_CUSTOM_ROLES) {
            throw new RoleException.LimitReached(MAX_CUSTOM_ROLES);
        }
        requireNameFree(tenantId, name, null);
        List<Permission> permissions = grantable(request.permissions(), roleScope);

        Role role = roleRepository.save(Role.builder()
                .code("custom_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .name(name)
                .description(blankToNull(request.description()))
                .systemRole(false)
                .scope(roleScope)
                .tenantId(tenantId)
                .build());
        savePermissions(role.getId(), permissions);

        auditApi.record(AuditEntryRequest.of(scope.userId(), "role.created", "role", role.getId(), tenantId,
                "Role '" + name + "' (" + roleScope.name().toLowerCase(Locale.ROOT) + ") created with "
                        + codes(permissions) + "."));
        log.info("Company role {} created for tenant {} by {}", role.getCode(), tenantId, scope.userId());
        return find(tenantId, role.getCode());
    }

    /**
     * Name, description and permissions. People holding the role get the new
     * permissions when their token next refreshes — within the access token's
     * 15 minutes, not instantly.
     */
    @Transactional
    public AssignableRoleDto update(String code, UpdateRoleRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        Role role = ownRole(code, tenantId);
        if (!rules.callerHoldsAllOf(List.of(role.getId()))) {
            throw new RoleException.Outranks();
        }
        if (request.scope() != null && parseScope(request.scope()) != role.getScope()) {
            throw new RoleException.ScopeImmutable();
        }

        List<String> changes = new ArrayList<>();
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new RoleException.InvalidName();
            }
            if (!name.equals(role.getName())) {
                requireNameFree(tenantId, name, role.getId());
                changes.add("name '" + role.getName() + "' -> '" + name + "'");
                role.setName(name);
            }
        }
        if (request.description() != null) {
            String description = blankToNull(request.description());
            if (!Objects.equals(description, role.getDescription())) {
                changes.add("description changed");
                role.setDescription(description);
            }
        }
        if (request.permissions() != null) {
            List<Permission> permissions = grantable(request.permissions(), role.getScope());
            Set<String> before = Set.copyOf(permissionRepository.findCodesByRoleIdIn(List.of(role.getId())));
            Set<String> after = permissions.stream().map(Permission::getCode).collect(Collectors.toSet());
            if (!before.equals(after)) {
                rolePermissionRepository.deleteByRoleId(role.getId());
                rolePermissionRepository.flush();
                savePermissions(role.getId(), permissions);
                changes.add("permissions " + sorted(before) + " -> " + sorted(after));
            }
        }
        if (changes.isEmpty()) {
            return find(tenantId, role.getCode());
        }
        roleRepository.save(role);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "role.updated", "role", role.getId(), tenantId,
                "Role '" + role.getName() + "': " + String.join("; ", changes) + "."));
        return find(tenantId, role.getCode());
    }

    /** Only a role nobody holds and no open invitation offers. Soft-deleted: past invitations still name it. */
    @Transactional
    public void delete(String code) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        Role role = ownRole(code, tenantId);
        if (!rules.callerHoldsAllOf(List.of(role.getId()))) {
            throw new RoleException.Outranks();
        }
        if (userRoleRepository.existsByRoleId(role.getId())) {
            throw new RoleException.InUse("someone still holds it (deactivated staff included). Give them another "
                    + "role first");
        }
        if (invitationRepository.existsOpenForRole(role.getId())) {
            throw new RoleException.InUse("an invitation or request offering it is still open. Revoke or reject it "
                    + "first");
        }
        role.setDeleted(true);
        roleRepository.save(role);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "role.deleted", "role", role.getId(), tenantId,
                "Role '" + role.getName() + "' deleted."));
    }

    /**
     * The permissions to put in a role: real {@code portal.*} ones, every one
     * held by the caller (SI-4), and none that needs company-wide staff unless
     * the role can only be given company-wide.
     */
    private List<Permission> grantable(List<String> requested, RoleScope roleScope) {
        Set<String> wanted = requested.stream().filter(Objects::nonNull).map(String::trim)
                .filter(c -> !c.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (wanted.isEmpty()) {
            throw new RoleException.NoPermissions();
        }
        Map<String, Permission> available = permissionRepository.findByCodeStartingWithOrderByCodeAsc(PORTAL_PREFIX)
                .stream().collect(Collectors.toMap(Permission::getCode, Function.identity()));
        List<String> unknown = wanted.stream().filter(c -> !available.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw new RoleException.UnknownPermission(String.join(", ", unknown));
        }
        Set<String> held = StaffRoleRules.heldAuthorities();
        List<String> notHeld = wanted.stream().filter(c -> !held.contains(c)).toList();
        if (!notHeld.isEmpty()) {
            throw new RoleException.CannotGrant(String.join(", ", notHeld));
        }
        List<Permission> permissions = wanted.stream().map(available::get).toList();
        if (roleScope != RoleScope.COMPANY) {
            List<String> companyOnly = permissions.stream()
                    .filter(p -> Boolean.TRUE.equals(p.getRequiresCompanyWide())).map(Permission::getCode).toList();
            if (!companyOnly.isEmpty()) {
                throw new RoleException.CompanyWidePermissionInNarrowRole(String.join(", ", companyOnly));
            }
        }
        return permissions;
    }

    private void savePermissions(UUID roleId, List<Permission> permissions) {
        rolePermissionRepository.saveAll(permissions.stream()
                .map(p -> RolePermission.builder().roleId(roleId).permissionId(p.getId()).build())
                .toList());
    }

    /** One of this company's own roles; a standard role is locked, another company's is not found. */
    private Role ownRole(String code, UUID tenantId) {
        return roleRepository.findByCodeAndTenantId(code, tenantId).orElseThrow(() ->
                roleRepository.findByCode(code).filter(r -> r.getTenantId() == null).isPresent()
                        ? new RoleException.StandardRoleLocked()
                        : new RoleException.NotFound());
    }

    /** Unique within the company, ignoring case — and never a standard role's name, which would read as one. */
    private void requireNameFree(UUID tenantId, String name, UUID except) {
        boolean standard = roleRepository.existsByTenantIdIsNullAndNameIgnoreCase(name);
        boolean taken = roleRepository.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name,
                except == null ? new UUID(0, 0) : except);
        if (standard || taken) {
            throw new RoleException.NameTaken(name);
        }
    }

    private AssignableRoleDto find(UUID tenantId, String code) {
        return rules.assignableRoles(tenantId).stream().filter(r -> r.code().equals(code)).findFirst()
                .orElseThrow(RoleException.NotFound::new);
    }

    private static RoleScope parseScope(String scope) {
        try {
            return RoleScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RoleException.InvalidScope(scope);
        }
    }

    private static UUID requireCompanyWide(TenantScope scope) {
        if (scope.tenantId() == null || scope.branchId() != null) {
            throw new RoleException.CompanyWideOnly();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException("No tenant scope on an authenticated request"));
    }

    private static String codes(List<Permission> permissions) {
        return permissions.stream().map(Permission::getCode).sorted().collect(Collectors.joining(", "));
    }

    private static List<String> sorted(Set<String> codes) {
        return codes.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
