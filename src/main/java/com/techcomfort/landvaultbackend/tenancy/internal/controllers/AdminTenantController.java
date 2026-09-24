package com.techcomfort.landvaultbackend.tenancy.internal.controllers;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateSupportAccessGrantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.ResubmitDocumentRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.SupportAccessGrantDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantPlanUpdateRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantStatusRequest;
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
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@Tag(name = OpenApiConfig.TAG_ADMIN_TENANTS)
public class AdminTenantController {

    private final AdminTenantService service;

    @Operation(summary = "The tenant directory",
            description = """
                    Requires `admin.tenants.view`. Platform scope: every company, across tenants.

                    Several fields are honestly absent rather than guessed: there is no stored \
                    "primary contact" anywhere in the schema, `statesOfOperation` is derived from \
                    what is stored rather than a column, and estate counts are 0 until the \
                    inventory read model supplies them.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of tenants")
    })
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

    @Operation(summary = "One tenant in full",
            description = """
                    Requires `admin.tenants.view`. Identity, documents, regulatory registrations, \
                    directors, financial details and the verification history.

                    **Director ID numbers are masked to the last four characters** and are never \
                    returned in full. `directors` is the most sensitive table in the system: the \
                    numbers are NDPR-regulated personal data.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content())
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<TenantDetailDto> getTenant(@PathVariable UUID id) {
        return service.getTenantDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Onboard a tenant",
            description = """
                    Requires `admin.tenants.manage`. Creates the company **and**, in the same \
                    transaction, its first Executive Director account — both or neither.

                    That account's password is randomly generated and never recoverable; the \
                    director resets it through the normal password-reset flow.

                    `rcNumber` is unique across the platform.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The created tenant"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content()),
            @ApiResponse(responseCode = "409", description = "That RC number, or the primary contact's email, is already registered", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTenant(request, currentUserId()));
    }

    @Operation(summary = "Move a tenant to documents-submitted",
            description = """
                    Requires `admin.tenants.manage`. One edge of the verification state machine. \
                    State only ever changes here, at `begin-review`, or at \
                    `verification-decision` — there is no side door where some other action quietly \
                    advances it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Not a legal transition from the current state", content = @Content())
    })
    @PostMapping("/{id}/submit-documents")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> submitDocuments(@PathVariable UUID id) {
        return ResponseEntity.ok(service.submitDocuments(id));
    }

    @Operation(summary = "Start compliance review",
            description = """
                    Requires `admin.tenants.manage`. Moves the tenant to `under_review` and records \
                    the reviewer as **the authenticated caller** — never a name from the request \
                    body, which would let anyone attribute a review to someone else.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Not a legal transition from the current state", content = @Content())
    })
    @PostMapping("/{id}/begin-review")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> beginReview(@PathVariable UUID id) {
        return ResponseEntity.ok(service.beginReview(id, currentUserId()));
    }

    @Operation(summary = "Record a verification decision",
            description = """
                    Requires `admin.tenants.manage`. `approved`, `rejected` or \
                    `request_more_info`.

                    **`request_more_info` does not change the verification state** — the tenant \
                    stays `under_review` while the reviewer waits for a clearer scan. Only \
                    `approved` and `rejected` move it.

                    **The decision cascades to the documents**: approval marks them all verified; \
                    rejection marks the ones named in `failedDocumentIds` rejected (copying the \
                    reason onto each) and the rest verified, because a rejection is a statement \
                    about specific evidence rather than the whole submission.

                    Decisions are append-only: a correction is a new decision, never an edit.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Not a legal transition from the current state", content = @Content())
    })
    @PostMapping("/{id}/verification-decision")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> recordVerificationDecision(
            @PathVariable UUID id, @Valid @RequestBody VerificationDecisionRequest request) {
        return ResponseEntity.ok(service.recordVerificationDecision(id, request, currentUserId()));
    }

    @Operation(summary = "Replace a rejected document",
            description = """
                    Requires `admin.tenants.manage`. Swaps the file's metadata and resets it to \
                    pending.

                    **It does not touch the tenant's verification state**: `submit-documents` and \
                    `begin-review` still have to run again. Re-uploading a file never quietly \
                    re-triggers a review nobody asked for.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant or document", content = @Content())
    })
    @PostMapping("/{id}/documents/{documentId}/resubmit")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> resubmitDocument(
            @PathVariable UUID id, @PathVariable UUID documentId, @Valid @RequestBody ResubmitDocumentRequest request) {
        return ResponseEntity.ok(service.resubmitDocument(id, documentId, request));
    }

    @Operation(summary = "Suspend, reactivate or offboard a tenant",
            description = """
                    Requires `admin.tenants.manage`. This is **portal access**, a different axis \
                    from verification: a company can be verified and suspended at once (compliant, \
                    but suspended for non-payment), or active and still under review.

                    `suspended` ↔ `active` moves freely. **`offboarded` is terminal** — no \
                    transition away from it is ever allowed, including back to active. Bringing a \
                    company back is a deliberate new-tenant decision, not a status flip.

                    Setting the status a tenant already has is refused rather than silently \
                    accepted: it usually means acting on a stale view.

                    Suspending also stops that company's staff logging in, and removes its estates \
                    from the public marketplace without unpublishing them.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`INVALID_STATUS_TRANSITION`: terminal, or already that status", content = @Content())
    })
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> changeStatus(@PathVariable UUID id, @Valid @RequestBody TenantStatusRequest request) {
        return ResponseEntity.ok(service.changeStatus(id, request, currentUserId()));
    }

    @Operation(summary = "Change plan and entitlements",
            description = """
                    Requires `admin.tenants.manage`. `marketplacePublishing` is the third of the \
                    five publication conditions: without it a company's estates never appear \
                    publicly, however they are configured.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated tenant"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content())
    })
    @PutMapping("/{id}/plan")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<TenantDetailDto> updatePlan(@PathVariable UUID id, @Valid @RequestBody TenantPlanUpdateRequest request) {
        return ResponseEntity.ok(service.updatePlan(id, request, currentUserId()));
    }

    @Operation(summary = "Grant yourself time-boxed support access",
            description = """
                    Requires `admin.tenants.manage`. Time-boxed, reason-required, and audited as \
                    **privileged**.

                    **This is a record, not a gate.** Creating a grant opens no door: a Super Admin \
                    already has the access. Its only job is producing the auditable record that \
                    somebody looked at a tenant's data, for this reason, in this window. Nothing \
                    checks for an active grant before allowing a read, and an expired grant blocks \
                    nothing — building that gate is a separate, larger decision.

                    The grantee is always the authenticated caller, never a name in the body.

                    Support access must never be usable to move money or sign documents.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded grant"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content())
    })
    @PostMapping("/{id}/support-access")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<SupportAccessGrantDto> grantSupportAccess(
            @PathVariable UUID id, @Valid @RequestBody CreateSupportAccessGrantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.grantSupportAccess(id, request, currentUserId()));
    }

    @Operation(summary = "List support-access grants",
            description = """
                    Requires `admin.tenants.manage`. Every grant on this tenant, including expired \
                    ones — the history is the point.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The grants"),
            @ApiResponse(responseCode = "404", description = "No such tenant", content = @Content())
    })
    @GetMapping("/{id}/support-access")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<List<SupportAccessGrantDto>> listSupportAccessGrants(@PathVariable UUID id) {
        return ResponseEntity.ok(service.listSupportAccessGrants(id));
    }

    private UUID currentUserId() {
        return TenantContext.get()
                .map(TenantScope::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
