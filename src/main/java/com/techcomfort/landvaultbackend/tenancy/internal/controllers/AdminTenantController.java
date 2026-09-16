package com.techcomfort.landvaultbackend.tenancy.internal.controllers;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantSummaryDto;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
import com.techcomfort.landvaultbackend.tenancy.internal.service.AdminTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The first two tenancy endpoints, and the first real exercise of the whole
 * stack by an actual client — JWT → tenant context filter → RLS platform
 * bypass → pagination envelope → enum wire casing. Both are platform-scope
 * reads: a Super Admin sees every tenant, which works because
 * {@code TenantScopeResolver} sets {@code platform_scope = on} for platform
 * staff and every RLS policy has a platform bypass. See AGENTS.md.
 */
@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class AdminTenantController {

    private final AdminTenantService service;

    @GetMapping
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<PageResponse<TenantSummaryDto>> listTenants(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> verificationState,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String submittedAfter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        List<VerificationState> states = verificationState == null
                ? null
                : verificationState.stream().map(VerificationState::fromValue).toList();
        TenantPlan planValue = plan == null ? null : TenantPlan.fromValue(plan);
        var createdAfter = submittedAfter == null || submittedAfter.isBlank()
                ? null
                : LocalDate.parse(submittedAfter).atStartOfDay(ZoneOffset.UTC).toInstant();

        var page = service.listTenants(query, states, planValue, state, createdAfter, PageResponses.pageable(cursor, limit));
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<TenantDetailDto> getTenant(@PathVariable UUID id) {
        return service.getTenantDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
