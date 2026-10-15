package com.techcomfort.landvaultbackend.tenancy.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestContactedRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestDeclineRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestDto;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.OnboardingRequestStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.service.OnboardingRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The Super Admin queue of companies asking to be onboarded. */
@RestController
@RequestMapping("/api/admin/onboarding-requests")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_TENANTS)
public class AdminOnboardingRequestController {

    private final OnboardingRequestService service;

    @Operation(summary = "Onboarding requests",
            description = """
                    Requires `admin.tenants.view`. Newest first. Filter with `status`: `new`, `contacted`, \
                    `converted` or `declined`. Each request carries `needsAttention` and `attentionReason`: \
                    `no_contact_yet` when it is still `new` after 2 working days (Monday to Friday, Lagos time; \
                    public holidays count as working days). `attention=true` lists only those, **waiting \
                    longest first**, and combines with `status`.""")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<PageResponse<OnboardingRequestDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean attention,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        OnboardingRequestStatus filter = status == null || status.isBlank()
                ? null : OnboardingRequestStatus.fromValue(status);
        return ResponseEntity.ok(service.list(filter, attention, PageResponses.pageable(cursor, limit)));
    }

    @Operation(summary = "One onboarding request", description = "Requires `admin.tenants.view`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request"),
            @ApiResponse(responseCode = "404", description = "No such request", content = @Content())
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<OnboardingRequestDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @Operation(summary = "Record that the company was contacted",
            description = "Requires `admin.tenants.manage`. `new` or `contacted` → `contacted`; repeatable, the "
                    + "latest contact and note are kept (every one is in the audit log).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "404", description = "No such request", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Already converted or declined", content = @Content())
    })
    @PostMapping("/{id}/contacted")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<OnboardingRequestDto> contacted(@PathVariable UUID id,
                                                          @Valid @RequestBody(required = false) OnboardingRequestContactedRequest body) {
        return ResponseEntity.ok(service.markContacted(id, body == null ? null : body.note(), currentUserId()));
    }

    @Operation(summary = "Decline a request",
            description = "Requires `admin.tenants.manage`. Final. The reason is kept for HomeTrue and is **not** "
                    + "emailed to the company; tell them yourself if appropriate.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined"),
            @ApiResponse(responseCode = "404", description = "No such request", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Already converted or declined", content = @Content())
    })
    @PostMapping("/{id}/decline")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<OnboardingRequestDto> decline(@PathVariable UUID id,
                                                        @Valid @RequestBody OnboardingRequestDeclineRequest body) {
        return ResponseEntity.ok(service.decline(id, body.reason(), currentUserId()));
    }

    @Operation(summary = "Convert a request into a tenant",
            description = """
                    Requires `admin.tenants.manage`. The body is exactly `POST /api/admin/tenants`'s — the \
                    request carries only what a first-contact form asks, so the rest (company type, \
                    incorporation date, the director's ID, the plan) is filled in here. Prefill it from \
                    the request.

                    Creates the company and invites its first Executive Director through the ordinary \
                    path, and marks the request `converted` with `organizationId` set — all or nothing: \
                    if creation fails (RC number or email already registered), the request stays open.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Converted; `organizationId` is the new tenant"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such request", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Already converted or declined, or that RC number or email is already registered",
                    content = @Content())
    })
    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<OnboardingRequestDto> convert(@PathVariable UUID id,
                                                        @Valid @RequestBody CreateTenantRequest body) {
        return ResponseEntity.ok(service.convert(id, body, currentUserId()));
    }

    private UUID currentUserId() {
        return TenantContext.get()
                .map(TenantScope::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
