package com.techcomfort.landvaultbackend.tenancy.internal.controllers;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.ResubmitDocumentRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantSummaryDto;
import com.techcomfort.landvaultbackend.tenancy.dto.VerificationDecisionRequest;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
import com.techcomfort.landvaultbackend.tenancy.internal.service.AdminTenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The tenant directory/detail reads (slice A), plus slice B1's create +
 * verification-lifecycle writes. Every write endpoint here requires
 * {@code admin.tenants.manage} (a stricter authority than the reads'
 * {@code admin.tenants.view}) and resolves the acting Super Admin's id from
 * {@link TenantContext} — set by identity's {@code TenantContextFilter} for
 * every authenticated request — rather than importing anything from
 * {@code identity} directly (which would reopen the module cycle
 * {@code TenantStaffAccountRequested}'s Javadoc describes). See AGENTS.md.
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

    @PostMapping
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTenant(request, currentUserId()));
    }

    @PostMapping("/{id}/submit-documents")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> submitDocuments(@PathVariable UUID id) {
        return ResponseEntity.ok(service.submitDocuments(id));
    }

    @PostMapping("/{id}/begin-review")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> beginReview(@PathVariable UUID id) {
        return ResponseEntity.ok(service.beginReview(id, currentUserId()));
    }

    @PostMapping("/{id}/verification-decision")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> recordVerificationDecision(
            @PathVariable UUID id, @Valid @RequestBody VerificationDecisionRequest request) {
        return ResponseEntity.ok(service.recordVerificationDecision(id, request, currentUserId()));
    }

    @PostMapping("/{id}/documents/{documentId}/resubmit")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> resubmitDocument(
            @PathVariable UUID id, @PathVariable UUID documentId, @Valid @RequestBody ResubmitDocumentRequest request) {
        return ResponseEntity.ok(service.resubmitDocument(id, documentId, request));
    }

    private UUID currentUserId() {
        return TenantContext.get()
                .map(TenantScope::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
