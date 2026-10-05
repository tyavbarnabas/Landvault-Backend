package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.dto.PortalBranchDto;
import com.techcomfort.landvaultbackend.tenancy.dto.UpdateBranchRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateBranchRequest;
import com.techcomfort.landvaultbackend.common.NigerianStates;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Branch;
import com.techcomfort.landvaultbackend.tenancy.internal.exceptions.PortalBranchException;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.Objects;
import java.util.ArrayList;
import java.util.UUID;

/**
 * TB-1..TB-3: a tenant's own branches. The company is always the caller's
 * own (from the token, via {@link TenantContext}), never the request; RLS on
 * {@code branches} (changeset 021) confines reads to it, and walls a
 * branch-scoped caller to their own branch.
 * <p>
 * Writes are company-wide only, twice over: the {@code portal.branches.manage}
 * permission is granted to {@code executive_director} alone, and a caller
 * whose current scope is one branch — including an Executive Director
 * narrowed with {@code X-Branch-Id} — is refused here. A branch-scoped user
 * who could create a sibling branch would carve out visibility for
 * themselves (TB-3). See AGENTS.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalBranchService {

    private final BranchRepository branchRepository;
    private final AuditApi auditApi;
    private final NigerianStates states;

    @Transactional(readOnly = true)
    public List<PortalBranchDto> list() {
        UUID tenantId = requireTenant(currentScope());
        return branchRepository.findByOrganizationId(tenantId).stream()
                .sorted(Comparator.comparing(Branch::getName, String.CASE_INSENSITIVE_ORDER))
                .map(PortalBranchService::toDto)
                .toList();
    }

    @Transactional
    public PortalBranchDto create(CreateBranchRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        String name = request.name().trim();
        if (branchRepository.existsByOrganizationIdAndNameIgnoreCase(tenantId, name)) {
            throw new PortalBranchException.NameTaken(name);
        }
        Branch branch = Branch.builder()
                .organizationId(tenantId)
                .name(name)
                .street(clean(request.street()))
                .city(clean(request.city()))
                .phone(clean(request.phone()))
                .email(clean(request.email()))
                .build();
        applyState(branch, request.state());
        // saveAndFlush: a lost race on uq_branches_organization_id_name
        // surfaces here, translated, as a 409 rather than at commit.
        branch = branchRepository.saveAndFlush(branch);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "tenant.branch_created", "branch", branch.getId(), tenantId,
                "Branch '" + name + "' created."));
        log.info("Branch {} created for tenant {} by {}", branch.getId(), tenantId, scope.userId());
        return toDto(branch);
    }

    /** TB-2: left out means unchanged, blank clears — except the name. Nothing changed, nothing recorded. */
    @Transactional
    public PortalBranchDto update(UUID branchId, UpdateBranchRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        Branch branch = branchRepository.findById(branchId)
                .filter(b -> b.getOrganizationId().equals(tenantId))
                .orElseThrow(PortalBranchException.NotFound::new);

        List<String> changes = new ArrayList<>();
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new PortalBranchException.BlankName();
            }
            String name = request.name().trim();
            if (!name.equals(branch.getName())) {
                if (branchRepository.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(tenantId, name, branchId)) {
                    throw new PortalBranchException.NameTaken(name);
                }
                changes.add("name '" + branch.getName() + "' -> '" + name + "'");
                branch.setName(name);
            }
        }
        change(changes, "street", branch.getStreet(), request.street(), branch::setStreet);
        change(changes, "city", branch.getCity(), request.city(), branch::setCity);
        change(changes, "phone", branch.getPhone(), request.phone(), branch::setPhone);
        change(changes, "email", branch.getEmail(), request.email(), branch::setEmail);
        if (request.state() != null) {
            String previous = branch.getState();
            applyState(branch, request.state());
            if (!Objects.equals(previous, branch.getState())) {
                changes.add("state '" + nullToEmpty(previous) + "' -> '" + nullToEmpty(branch.getState()) + "'");
            }
        }

        if (changes.isEmpty()) {
            return toDto(branch);
        }
        branch = branchRepository.saveAndFlush(branch);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "tenant.branch_updated", "branch", branchId, tenantId,
                "Branch '" + branch.getName() + "' changed: " + String.join("; ", changes) + "."));
        return toDto(branch);
    }

    /** Blank clears; anything else must be a real state, stored by its canonical name and code. */
    private void applyState(Branch branch, String input) {
        if (input == null || input.isBlank()) {
            if (input != null) {
                branch.setState(null);
                branch.setStateCode(null);
            }
            return;
        }
        NigerianStates.State state = states.resolve(input)
                .orElseThrow(() -> new PortalBranchException.UnknownState(input, states.names()));
        branch.setState(state.name());
        branch.setStateCode(state.code());
    }

    private static void change(List<String> changes, String field, String current, String requested,
                               Consumer<String> setter) {
        if (requested == null) {
            return;
        }
        String value = clean(requested);
        if (!Objects.equals(value, current)) {
            changes.add(field + " '" + nullToEmpty(current) + "' -> '" + nullToEmpty(value) + "'");
            setter.accept(value);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static UUID requireCompanyWide(TenantScope scope) {
        UUID tenantId = requireTenant(scope);
        if (scope.branchId() != null) {
            throw new PortalBranchException.CompanyWideOnly();
        }
        return tenantId;
    }

    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new PortalBranchException.NoTenant();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    private static PortalBranchDto toDto(Branch branch) {
        return new PortalBranchDto(branch.getId(), branch.getName(), branch.getStreet(), branch.getCity(),
                branch.getState(), branch.getPhone(), branch.getEmail(), branch.getCreatedAt());
    }
}
