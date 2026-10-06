package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.identity.dto.AssignableRoleDto;
import com.techcomfort.landvaultbackend.identity.internal.service.StaffRoleRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The roles a company can give its staff — so the portal never hard-codes them. */
@RestController
@RequestMapping("/api/portal/roles")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_STAFF)
public class PortalRoleController {

    private final StaffRoleRules rules;

    @Operation(summary = "List the roles your company can assign",
            description = """
                    Requires `portal.staff.invite` or `portal.staff.request`. Every tenant role with its \
                    scope (`company` / `branch` / `either`), its permissions, and `canGrant` — whether \
                    *you* could invite into it directly. Buyer and platform roles never appear. Today \
                    these are the platform's standard roles (`custom: false`); company-made roles will \
                    appear here too when they exist.""")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('portal.staff.invite', 'portal.staff.request')")
    public ResponseEntity<List<AssignableRoleDto>> list() {
        return ResponseEntity.ok(rules.assignableRoles());
    }
}
