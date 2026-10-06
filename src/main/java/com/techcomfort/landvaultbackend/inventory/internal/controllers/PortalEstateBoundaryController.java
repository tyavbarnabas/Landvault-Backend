package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.inventory.dto.BoundaryChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.CorrectEstateBoundaryRequest;
import com.techcomfort.landvaultbackend.inventory.internal.service.EstateBoundaryCorrectionService;
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

/** FP-2: correcting an estate's existing boundary. See {@link EstateBoundaryCorrectionService}. */
@RestController
@RequestMapping("/api/portal/estates/{estateId}")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_ESTATES)
public class PortalEstateBoundaryController {

    private final EstateBoundaryCorrectionService service;

    @Operation(summary = "Correct an estate's boundary",
            description = """
                    Requires `portal.estates.manage`. Replaces an existing boundary (an estate with none uses \
                    `POST .../boundary`). Coordinates are **[longitude, latitude]**. `reason` is required.

                    - Same checks as adding one: inside the estate's state, and every mapped plot still \
                    inside (`PLOT_OUTSIDE_ESTATE` names any that wouldn't be).
                    - **Applied at once (200, `applied`)** when the estate isn't published, or when no more \
                    than 5% of its land changes (land added plus land removed). The response then reports \
                    what overlap detection found.
                    - **Held for review (202, `pending`)** when a published estate's land changes by more: \
                    the current boundary stays live until a Super Admin decides.
                    - A cross-company overlap that this clears still needs a Super Admin to close it; a new \
                    one takes the estate off the marketplace. The other company is never named.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Applied"),
            @ApiResponse(responseCode = "202", description = "Held for Super Admin review"),
            @ApiResponse(responseCode = "400", description = "Invalid geometry, `PLOT_OUTSIDE_ESTATE`, `BOUNDARY_OUTSIDE_STATE`, `BOUNDARY_UNCHANGED`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`BOUNDARY_NOT_SET` or `BOUNDARY_CHANGE_PENDING`", content = @Content())
    })
    @PutMapping("/boundary")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<BoundaryChangeDto> correct(@PathVariable UUID estateId,
                                                     @Valid @RequestBody CorrectEstateBoundaryRequest request) {
        BoundaryChangeDto result = service.correct(estateId, request);
        return ResponseEntity.status("pending".equals(result.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    @Operation(summary = "An estate's boundary history",
            description = "Requires `portal.estates.view`. Every correction, newest first, with both shapes: applied, pending, approved, rejected (with the reviewer's note) or withdrawn.")
    @GetMapping("/boundary-changes")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<List<BoundaryChangeDto>> history(@PathVariable UUID estateId) {
        return ResponseEntity.ok(service.history(estateId));
    }

    @Operation(summary = "Withdraw a correction waiting for review",
            description = "Requires `portal.estates.manage`. Only while `pending` (409 `BOUNDARY_CHANGE_NOT_PENDING` otherwise). The current boundary is unaffected.")
    @PostMapping("/boundary-changes/{changeId}/withdraw")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<BoundaryChangeDto> withdraw(@PathVariable UUID estateId, @PathVariable UUID changeId) {
        return ResponseEntity.ok(service.withdraw(estateId, changeId));
    }
}
