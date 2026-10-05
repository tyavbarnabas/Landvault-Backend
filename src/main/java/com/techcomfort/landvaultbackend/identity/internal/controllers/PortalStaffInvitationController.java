package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.identity.dto.CreateStaffInvitationRequest;
import com.techcomfort.landvaultbackend.identity.dto.RejectInvitationRequest;
import com.techcomfort.landvaultbackend.identity.dto.StaffInvitationDto;
import com.techcomfort.landvaultbackend.identity.internal.service.StaffInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * SI-1, SI-2, SI-4..SI-6: a company invites its own staff, and a branch asks
 * head office to invite someone. See {@link StaffInvitationService}.
 */
@RestController
@RequestMapping("/api/portal/staff/invitations")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_STAFF)
public class PortalStaffInvitationController {

    private final StaffInvitationService service;

    @Operation(summary = "Invite a member of staff",
            description = """
                    Requires `portal.staff.invite` (Executive Director) and a company-wide view. Staff \
                    are always invited — registration only ever creates buyers.

                    - `roleCode` must be a tenant role (`executive_director`, `branch_manager`, \
                    `sales_manager`, `surveyor_project_manager`, `finance_officer`, `legal_officer`). \
                    You can't invite into a role carrying permissions you don't hold.
                    - `branchId` is **required** for `branch_manager`, **refused** for \
                    `executive_director`, optional for the rest. It must be one of your branches.
                    - The email must have **no LandVault account** — staff use a work email; an \
                    existing account can't also be given a company role.

                    An email with a single-use link goes to the person; it expires in 72 hours. \
                    The link is never returned here.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invited; the email is on its way"),
            @ApiResponse(responseCode = "400", description = "`ROLE_NOT_INVITABLE`, `ROLE_SCOPE_MISMATCH`, `BRANCH_NOT_FOUND`, or validation", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`CANNOT_GRANT_ROLE`, `INVITATIONS_REQUIRE_COMPANY_WIDE_SCOPE`, `TENANT_NOT_ACTIVE`, or no permission", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`EMAIL_HAS_ACCOUNT` or `INVITATION_ALREADY_PENDING`", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffInvitationDto> invite(@Valid @RequestBody CreateStaffInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.invite(request));
    }

    @Operation(summary = "Ask head office to invite someone to your branch",
            description = """
                    Requires `portal.staff.request` (Branch Manager). The same body as an invitation, but                     **nothing is sent to the person yet**: the request waits for a company-wide approver                     (the Executive Director, who is emailed about it) and lapses after 14 days.

                    - The branch is always **yours** — leave `branchId` out.
                    - Company-wide roles (`executive_director`) can't be requested.
                    - You can't request a role carrying permissions you don't hold.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Requested; status `awaiting_approval`"),
            @ApiResponse(responseCode = "400", description = "`ROLE_NOT_INVITABLE`, `ROLE_SCOPE_MISMATCH`, or validation", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`CANNOT_GRANT_ROLE`, `INVITATION_REQUESTS_REQUIRE_BRANCH_SCOPE`, `TENANT_NOT_ACTIVE`, or no permission", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`EMAIL_HAS_ACCOUNT` or `INVITATION_ALREADY_PENDING`", content = @Content())
    })
    @PostMapping("/requests")
    @PreAuthorize("hasAuthority('portal.staff.request')")
    public ResponseEntity<StaffInvitationDto> request(@Valid @RequestBody CreateStaffInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.request(request));
    }

    @Operation(summary = "Approve a branch's request",
            description = """
                    Requires `portal.staff.invite` and a company-wide view. Sends the ordinary invitation                     email with a fresh 72-hour link. A request that lapsed (14 days) can't be approved —                     reject it and the branch can ask again.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved; status `pending`, the email is on its way"),
            @ApiResponse(responseCode = "409", description = "`INVITATION_NOT_AWAITING_APPROVAL`, `INVITATION_REQUEST_EXPIRED` or `EMAIL_HAS_ACCOUNT`", content = @Content())
    })
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffInvitationDto> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(service.approve(id));
    }

    @Operation(summary = "Reject a branch's request",
            description = "Requires `portal.staff.invite` and a company-wide view. `reason` is required and shown to the branch manager. Nothing was ever sent to the person.")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffInvitationDto> reject(@PathVariable UUID id,
                                                     @Valid @RequestBody RejectInvitationRequest request) {
        return ResponseEntity.ok(service.reject(id, request.reason()));
    }

    @Operation(summary = "Withdraw your own request",
            description = "Requires `portal.staff.request`. Only the branch manager who asked, only while it awaits approval. Someone else's request is a 404.")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('portal.staff.request')")
    public ResponseEntity<StaffInvitationDto> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(service.cancel(id));
    }

    @Operation(summary = "List your company's invitations",
            description = """
                    Requires `portal.staff.invite` or `portal.staff.request`. Company-wide callers see every                     invitation; a branch manager sees their own branch's. Newest first, with status:                     awaiting_approval, rejected, pending, expired, accepted or revoked.""")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('portal.staff.invite', 'portal.staff.request')")
    public ResponseEntity<List<StaffInvitationDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    @Operation(summary = "Revoke an invitation",
            description = "Requires `portal.staff.invite`. The link stops working immediately. Accepted or already-revoked invitations can't be revoked (409).")
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffInvitationDto> revoke(@PathVariable UUID id) {
        return ResponseEntity.ok(service.revoke(id));
    }

    @Operation(summary = "Resend an invitation",
            description = """
                    Requires `portal.staff.invite`. Sends a **new** link — the previous one stops working, \
                    so there are never two — and restarts the 72-hour clock, including for an expired \
                    invitation. Rate-limited: not within 2 minutes of the last send, and at most 5 sends \
                    in total (429).""")
    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffInvitationDto> resend(@PathVariable UUID id) {
        return ResponseEntity.ok(service.resend(id));
    }
}
