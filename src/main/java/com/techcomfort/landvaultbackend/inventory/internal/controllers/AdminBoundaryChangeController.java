package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.inventory.dto.BoundaryChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.BoundaryDecisionRequest;
import com.techcomfort.landvaultbackend.inventory.internal.enums.BoundaryChangeStatus;
import com.techcomfort.landvaultbackend.inventory.internal.service.EstateBoundaryCorrectionService;
import io.swagger.v3.oas.annotations.Operation;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reviewing large boundary corrections to published estates. Gated on
 * {@code admin.marketplace.conflicts}: the people who rule on boundary
 * disputes between companies, and on disputed state borders.
 */
@RestController
@RequestMapping("/api/admin/boundary-changes")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_CONFLICTS)
public class AdminBoundaryChangeController {

    private final EstateBoundaryCorrectionService service;

    @Operation(summary = "Boundary corrections by status",
            description = "Requires `admin.marketplace.conflicts`. Defaults to `pending`, oldest first, with both shapes, the share of land changed, the developer's reason and the company.")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<List<BoundaryChangeDto>> list(@RequestParam(defaultValue = "pending") String status) {
        return ResponseEntity.ok(service.listForReview(BoundaryChangeStatus.valueOf(status.trim().toUpperCase(Locale.ROOT))));
    }

    @Operation(summary = "Approve a boundary correction",
            description = """
                    Requires `admin.marketplace.conflicts`. Re-checks the state and that every plot still \
                    fits (plots may have been added while it waited), then applies the new boundary and \
                    re-runs overlap detection. The response reports what detection found. `note` is optional.""")
    @PostMapping("/{changeId}/approve")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<BoundaryChangeDto> approve(@PathVariable UUID changeId,
                                                     @Valid @RequestBody(required = false) BoundaryDecisionRequest request) {
        return ResponseEntity.ok(service.approve(changeId, request == null ? null : request.note()));
    }

    @Operation(summary = "Reject a boundary correction",
            description = "Requires `admin.marketplace.conflicts`. `note` is required — the developer sees it. The previous boundary stays.")
    @PostMapping("/{changeId}/reject")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<BoundaryChangeDto> reject(@PathVariable UUID changeId,
                                                    @Valid @RequestBody BoundaryDecisionRequest request) {
        return ResponseEntity.ok(service.reject(changeId, request.note()));
    }
}
