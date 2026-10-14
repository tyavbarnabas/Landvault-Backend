package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.identity.dto.AssignableRoleDto;
import com.techcomfort.landvaultbackend.identity.dto.CreateRoleRequest;
import com.techcomfort.landvaultbackend.identity.dto.PortalPermissionDto;
import com.techcomfort.landvaultbackend.identity.dto.UpdateRoleRequest;
import com.techcomfort.landvaultbackend.identity.internal.service.PortalRoleService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The roles a company can give its staff — HomeTrue's standard ones and its own — so the portal never hard-codes them. */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_STAFF)
public class PortalRoleController {

    private final PortalRoleService service;

    @Operation(summary = "List the roles your company can assign",
            description = """
                    Requires `portal.staff.invite`, `portal.staff.request` or `portal.roles.manage`. HomeTrue's \
                    standard roles (`custom: false`) and your company's own (`custom: true`) — never another \
                    company's. Each with its scope (`company` / `branch` / `either`), its permissions, and \
                    `canGrant` — whether *you* could invite into it directly. Buyer and platform roles never appear.""")
    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('portal.staff.invite', 'portal.staff.request', 'portal.roles.manage')")
    public ResponseEntity<List<AssignableRoleDto>> list() {
        return ResponseEntity.ok(service.roles());
    }

    @Operation(summary = "The permissions a company role can carry",
            description = """
                    Requires `portal.roles.manage`. Every `portal.*` permission with a short `label` for a \
                    checkbox, whether it `requiresCompanyWide` (then it can go only in a `company` role), and \
                    whether you `held` it — you can only put permissions you hold into a role. Permissions \
                    themselves are HomeTrue's; a company can't invent one.""")
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('portal.roles.manage')")
    public ResponseEntity<List<PortalPermissionDto>> permissions() {
        return ResponseEntity.ok(service.permissions());
    }

    @Operation(summary = "Create a role of your own",
            description = """
                    Requires `portal.roles.manage` and a company-wide view. A name (unique in your company, and \
                    not one of HomeTrue's standard role names), a scope, and `portal.*` permissions you hold. \
                    A role carrying a company-wide-only permission must have scope `company`, because staff \
                    given it for one branch would be refused by those screens. At most 30 roles per company. \
                    The response's `code` is how invitations and role changes name it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Unknown permission, bad scope, or a company-wide-only "
                    + "permission in a branch/either role", content = @Content()),
            @ApiResponse(responseCode = "403", description = "A permission you don't hold, or a branch-scoped view",
                    content = @Content()),
            @ApiResponse(responseCode = "409", description = "Name taken, or 30 roles already", content = @Content())
    })
    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('portal.roles.manage')")
    public ResponseEntity<AssignableRoleDto> create(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "Edit one of your roles",
            description = """
                    Requires `portal.roles.manage`. Name, description and permissions; left out means \
                    unchanged, and the scope can't change. Same rules as creating, and you must hold everything \
                    the role carries now too. **People holding the role get the change when their sign-in next \
                    refreshes — within 15 minutes, not instantly.** HomeTrue's standard roles are locked (403).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated (or unchanged)"),
            @ApiResponse(responseCode = "403", description = "A standard role, or more than you hold", content = @Content()),
            @ApiResponse(responseCode = "404", description = "Not one of your company's roles", content = @Content())
    })
    @PutMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('portal.roles.manage')")
    public ResponseEntity<AssignableRoleDto> update(@PathVariable String code,
                                                    @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(service.update(code, request));
    }

    @Operation(summary = "Delete one of your roles",
            description = """
                    Requires `portal.roles.manage`. Only when nobody holds it (deactivated staff count) and no \
                    open invitation or branch request offers it — otherwise 409 `ROLE_IN_USE`. HomeTrue's \
                    standard roles can't be deleted.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "A standard role", content = @Content()),
            @ApiResponse(responseCode = "404", description = "Not one of your company's roles", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Still held or offered", content = @Content())
    })
    @DeleteMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('portal.roles.manage')")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        service.delete(code);
        return ResponseEntity.noContent().build();
    }
}
