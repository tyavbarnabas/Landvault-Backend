package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.inventory.dto.EstateStateOverrideDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateStateOverrideRequest;
import com.techcomfort.landvaultbackend.inventory.internal.service.AdminEstateStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * SB-1's override, for Super Admins. Gated on {@code admin.marketplace.conflicts}:
 * the people who already rule on boundary disputes between companies rule on
 * a boundary disputed between states too — no new slug for one route.
 */
@RestController
@RequestMapping("/api/admin/estates")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_CONFLICTS)
public class AdminEstateStateController {

    private final AdminEstateStateService service;

    @Operation(summary = "Verify an estate's state by hand (disputed border)",
            description = """
                    Requires `admin.marketplace.conflicts`. For land on a genuinely disputed state \
                    border, where the reference boundaries (GRID3) and the land registry disagree: \
                    records that a person verified the declared state, and **skips the \
                    boundary-in-state check for this estate**. The developer then adds the boundary \
                    with `POST /api/portal/estates/{id}/boundary`.

                    `reason` is required and kept. Changing the estate's state clears the override — \
                    it verified the old one.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override recorded"),
            @ApiResponse(responseCode = "400", description = "No reason given", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate", content = @Content())
    })
    @PostMapping("/{id}/state-override")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<EstateStateOverrideDto> setOverride(
            @PathVariable UUID id, @Valid @RequestBody EstateStateOverrideRequest request) {
        return ResponseEntity.ok(service.setOverride(id, request.reason()));
    }

    @Operation(summary = "Remove an estate's state override",
            description = "Requires `admin.marketplace.conflicts`. The boundary-in-state check applies again to future boundary writes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override removed (or there was none)"),
            @ApiResponse(responseCode = "404", description = "No such estate", content = @Content())
    })
    @DeleteMapping("/{id}/state-override")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<EstateStateOverrideDto> clearOverride(@PathVariable UUID id) {
        return ResponseEntity.ok(service.clearOverride(id));
    }
}
