package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.identity.dto.ChangeStaffRoleRequest;
import com.techcomfort.landvaultbackend.identity.dto.DeactivateStaffRequest;
import com.techcomfort.landvaultbackend.identity.dto.StaffMemberDto;
import com.techcomfort.landvaultbackend.identity.internal.service.PortalStaffService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A company manages the staff it already has. See {@link PortalStaffService}. */
@RestController
@RequestMapping("/api/portal/staff")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_STAFF)
public class PortalStaffController {

    private final PortalStaffService service;

    @Operation(summary = "List your company's staff",
            description = """
                    Requires `portal.staff.invite` or `portal.staff.request`. Company-wide callers see \
                    everyone; a branch manager sees the people with a role in their branch. Each person's \
                    roles are listed with their branch (`branchId` null = company-wide).""")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('portal.staff.invite', 'portal.staff.request')")
    public ResponseEntity<List<StaffMemberDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    @Operation(summary = "Change someone's role",
            description = """
                    Requires `portal.staff.invite` and a company-wide view. **Replaces** every role the \
                    person holds with this one, under the same rules as an invitation (role scope decides \
                    the branch; you can't grant permissions you don't hold). You can't change your own \
                    role, someone who holds permissions you don't, or the company's last active \
                    Executive Director. Their sessions end; the new role applies at their next sign-in \
                    (an already-issued access token lasts up to 15 minutes).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed (or already that role — no change recorded)"),
            @ApiResponse(responseCode = "400", description = "`ROLE_NOT_INVITABLE`, `ROLE_SCOPE_MISMATCH`, `BRANCH_NOT_FOUND`, `CANNOT_MANAGE_YOURSELF`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`CANNOT_GRANT_ROLE`, `CANNOT_MANAGE_STAFF_MEMBER`, `STAFF_MANAGEMENT_REQUIRES_COMPANY_WIDE_SCOPE`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`STAFF_NOT_FOUND` (including another company's user)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`LAST_EXECUTIVE_DIRECTOR`", content = @Content())
    })
    @PutMapping("/{userId}/role")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffMemberDto> changeRole(@PathVariable UUID userId,
                                                     @Valid @RequestBody ChangeStaffRoleRequest request) {
        return ResponseEntity.ok(service.changeRole(userId, request));
    }

    @Operation(summary = "Deactivate someone",
            description = """
                    Requires `portal.staff.invite` and a company-wide view; `reason` is required and goes to \
                    the audit log. They can no longer sign in or refresh; an already-issued access token \
                    lasts up to 15 minutes — this is not instant. Same limits as a role change.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deactivated"),
            @ApiResponse(responseCode = "409", description = "`STAFF_ALREADY_DEACTIVATED` or `LAST_EXECUTIVE_DIRECTOR`", content = @Content())
    })
    @PostMapping("/{userId}/deactivate")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffMemberDto> deactivate(@PathVariable UUID userId,
                                                     @Valid @RequestBody DeactivateStaffRequest request) {
        return ResponseEntity.ok(service.deactivate(userId, request.reason()));
    }

    @Operation(summary = "Reactivate someone",
            description = "Requires `portal.staff.invite` and a company-wide view. Only a deactivated account (409 `STAFF_NOT_DEACTIVATED` otherwise). Their role is unchanged.")
    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("hasAuthority('portal.staff.invite')")
    public ResponseEntity<StaffMemberDto> reactivate(@PathVariable UUID userId) {
        return ResponseEntity.ok(service.reactivate(userId));
    }
}
