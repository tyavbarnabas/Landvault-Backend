package com.techcomfort.landvaultbackend.tenancy.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateBranchRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.UpdateBranchRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PortalBranchDto;
import com.techcomfort.landvaultbackend.tenancy.internal.service.PortalBranchService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** TB-1..TB-3: a tenant's own branches. See {@link PortalBranchService}. */
@RestController
@RequestMapping("/api/portal/branches")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_BRANCHES)
public class PortalBranchController {

    private final PortalBranchService service;

    @Operation(summary = "List your company's branches",
            description = """
                    Any tenant staff. Company-wide staff see every branch; branch-scoped staff see \
                    only their own — enforced by the database, not by this route. A company with no \
                    branches gets an empty list, never an invented "Head Office".""")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('portal.branches.manage', 'portal.estates.view')")
    public ResponseEntity<List<PortalBranchDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    @Operation(summary = "Create a branch",
            description = """
                    Requires `portal.branches.manage` (Executive Director) **and** a company-wide view: \
                    a caller narrowed to one branch is refused. The branch is created in your own \
                    company — never one named in the request. Names are unique within the company, \
                    ignoring case.

                    The office details (street, city, state, phone, email) are optional and \
                    **public**: they appear on this branch's marketplace listings so buyers can visit \
                    or call. `state` accepts a name, ISO code or common spelling and is stored under \
                    its standard name.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Blank or overlong name", content = @Content()),
            @ApiResponse(responseCode = "403", description = "No permission, or `BRANCHES_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`BRANCH_NAME_TAKEN`", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('portal.branches.manage')")
    public ResponseEntity<PortalBranchDto> create(@Valid @RequestBody CreateBranchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "Update a branch's name or office details",
            description = """
                    Same rules as creating one. Every field is optional — left out means unchanged; a \
                    blank value clears it, except the name. A request that changes nothing records \
                    nothing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated (or unchanged)"),
            @ApiResponse(responseCode = "403", description = "No permission, or `BRANCHES_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "Not one of your company's branches", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`BRANCH_NAME_TAKEN`", content = @Content())
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('portal.branches.manage')")
    public ResponseEntity<PortalBranchDto> update(@PathVariable UUID id, @Valid @RequestBody UpdateBranchRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }
}
