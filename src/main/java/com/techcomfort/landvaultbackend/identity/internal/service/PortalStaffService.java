package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.ChangeStaffRoleRequest;
import com.techcomfort.landvaultbackend.identity.dto.StaffMemberDto;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.StaffException;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A company manages the staff it has: list them, change a role, deactivate
 * and reactivate. Changes are company-wide only, never on yourself, never on
 * someone holding permissions you don't (SI-4 in the other direction), and
 * never leave a company without an active Executive Director. See AGENTS.md,
 * "Managing staff".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalStaffService {

    private static final String EXECUTIVE_DIRECTOR = "executive_director";
    private static final String BUYER = "buyer";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final TenancyApi tenancyApi;
    private final AuditApi auditApi;
    private final AuthService authService;
    private final StaffRoleRules rules;

    /** Company-wide callers see everyone; a branch-scoped caller sees people with a role in their branch. */
    @Transactional(readOnly = true)
    public List<StaffMemberDto> list() {
        TenantScope scope = currentScope();
        if (scope.tenantId() == null) {
            throw new StaffException.CompanyWideOnly();
        }
        List<User> users = userRepository.findByTenantIdOrderByCreatedAtAsc(scope.tenantId());
        Map<UUID, List<UserRole>> assignments = userRoleRepository
                .findByUserIdIn(users.stream().map(User::getId).toList()).stream()
                .collect(Collectors.groupingBy(ur -> ur.getUser().getId()));
        Map<UUID, Role> roles = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<UUID, String> branchNames = new HashMap<>();

        return users.stream()
                .filter(u -> scope.branchId() == null || assignments.getOrDefault(u.getId(), List.of()).stream()
                        .anyMatch(ur -> scope.branchId().equals(ur.getScopedBranchId())))
                .map(u -> toDto(u, assignments.getOrDefault(u.getId(), List.of()), roles, branchNames, scope.tenantId()))
                .toList();
    }

    /**
     * Replaces every role the person holds with one. The old assignments are
     * removed, not soft-deleted: the unique constraint on (user, role, branch)
     * counts deleted rows, so a soft delete would make a role impossible to
     * give back. The audit entry is the history. Their sessions end, so the
     * new role applies at their next sign-in — within the access token's
     * 15 minutes at the latest.
     */
    @Transactional
    public StaffMemberDto changeRole(UUID userId, ChangeStaffRoleRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        User target = manageable(userId, tenantId, scope);
        List<UserRole> current = userRoleRepository.findByUserId(userId);

        Role role = rules.assignableRole(request.roleCode());
        String branchName = rules.branchFor(role, request.branchId(), tenantId);
        rules.requireCallerHolds(role);

        if (current.size() == 1 && current.getFirst().getRoleId().equals(role.getId())
                && Objects.equals(current.getFirst().getScopedBranchId(), request.branchId())) {
            return single(target);
        }
        boolean staysCompanyWideExecutive = role.getCode().equals(EXECUTIVE_DIRECTOR) && request.branchId() == null;
        if (!staysCompanyWideExecutive) {
            requireAnotherExecutive(target, tenantId);
        }

        String before = describe(current, tenantId);
        userRoleRepository.deleteAll(current);
        userRoleRepository.flush();
        userRoleRepository.save(UserRole.builder()
                .user(target)
                .roleId(role.getId())
                .scopedBranchId(request.branchId())
                .grantedByUserId(scope.userId())
                .build());
        authService.revokeAllSessions(userId);

        auditApi.record(AuditEntryRequest.of(scope.userId(), "staff.role_changed", "user", userId, tenantId,
                target.getEmail() + ": " + before + " → " + role.getCode()
                        + (branchName == null ? " (company-wide)" : " in branch '" + branchName + "'")
                        + ". Their sessions were ended."));
        return single(target);
    }

    /** Cuts off portal access: sign-in and refresh are refused; an access token rides out its 15 minutes. */
    @Transactional
    public StaffMemberDto deactivate(UUID userId, String reason) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        User target = manageable(userId, tenantId, scope);
        if (target.getStatus() == UserStatus.DEACTIVATED) {
            throw new StaffException.AlreadyDeactivated();
        }
        requireAnotherExecutive(target, tenantId);

        target.setStatus(UserStatus.DEACTIVATED);
        userRepository.save(target);
        authService.revokeAllSessions(userId);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "staff.deactivated", "user", userId, tenantId,
                target.getEmail() + " deactivated: " + reason.trim()));
        return single(target);
    }

    @Transactional
    public StaffMemberDto reactivate(UUID userId) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        User target = manageable(userId, tenantId, scope);
        if (target.getStatus() != UserStatus.DEACTIVATED) {
            throw new StaffException.NotDeactivated();
        }
        target.setStatus(UserStatus.ACTIVE);
        userRepository.save(target);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "staff.reactivated", "user", userId, tenantId,
                target.getEmail() + " reactivated."));
        return single(target);
    }

    // --- rules ---

    /** Someone in the caller's company, not the caller, and holding nothing the caller doesn't. */
    private User manageable(UUID userId, UUID tenantId, TenantScope scope) {
        User target = userRepository.findById(userId)
                .filter(u -> tenantId.equals(u.getTenantId()))
                .orElseThrow(StaffException.NotFound::new);
        if (target.getId().equals(scope.userId())) {
            throw new StaffException.Yourself();
        }
        // A leftover buyer role (from sign-up, before invitations) isn't power
        // over the company — and replacing it is what restores a branch wall
        // (AGENTS.md, "leftover buyer role") — so it doesn't count here.
        UUID buyerRoleId = roleRepository.findByCode(BUYER).map(Role::getId).orElse(null);
        List<UUID> heldRoleIds = userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId)
                .filter(id -> !id.equals(buyerRoleId))
                .toList();
        if (!rules.callerHoldsAllOf(heldRoleIds)) {
            throw new StaffException.Outranks();
        }
        return target;
    }

    /** Only matters when the target is themselves an active company-wide Executive Director. */
    private void requireAnotherExecutive(User target, UUID tenantId) {
        List<User> executives = userRepository.findCompanyWideHolders(tenantId, EXECUTIVE_DIRECTOR, UserStatus.ACTIVE);
        boolean targetIsOne = executives.stream().anyMatch(u -> u.getId().equals(target.getId()));
        if (targetIsOne && executives.size() == 1) {
            throw new StaffException.LastExecutive();
        }
    }

    private StaffMemberDto single(User user) {
        Map<UUID, Role> roles = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        return toDto(user, userRoleRepository.findByUserId(user.getId()), roles, new HashMap<>(), user.getTenantId());
    }

    private String describe(List<UserRole> assignments, UUID tenantId) {
        if (assignments.isEmpty()) {
            return "(no role)";
        }
        Map<UUID, Role> roles = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        return assignments.stream()
                .map(ur -> roles.get(ur.getRoleId()).getCode() + (ur.getScopedBranchId() == null ? "" : " in branch '"
                        + tenancyApi.branchNameFor(ur.getScopedBranchId(), tenantId).orElse("?") + "'"))
                .collect(Collectors.joining(", "));
    }

    private StaffMemberDto toDto(User u, List<UserRole> assignments, Map<UUID, Role> roles,
                                 Map<UUID, String> branchNames, UUID tenantId) {
        List<StaffMemberDto.StaffRoleDto> roleDtos = assignments.stream()
                .sorted(Comparator.comparing(UserRole::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ur -> {
                    Role role = roles.get(ur.getRoleId());
                    String branchName = ur.getScopedBranchId() == null ? null : branchNames.computeIfAbsent(
                            ur.getScopedBranchId(), id -> tenancyApi.branchNameFor(id, tenantId).orElse(null));
                    return new StaffMemberDto.StaffRoleDto(role.getCode(), role.getName(), ur.getScopedBranchId(), branchName);
                })
                .toList();
        return new StaffMemberDto(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getPhone(),
                u.getStatus().getValue(), roleDtos, u.getLastLoginAt(), u.getCreatedAt());
    }

    private static UUID requireCompanyWide(TenantScope scope) {
        if (scope.tenantId() == null || scope.branchId() != null) {
            throw new StaffException.CompanyWideOnly();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
