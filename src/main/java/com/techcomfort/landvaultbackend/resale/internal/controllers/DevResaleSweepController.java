package com.techcomfort.landvaultbackend.resale.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import com.techcomfort.landvaultbackend.resale.internal.service.ResaleListingSweeper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Development only: runs the resale listing sweep now. Not present outside the dev profile at all. */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/resale")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_RESALE)
public class DevResaleSweepController {

    private final ResaleListingSweeper sweeper;

    @Operation(summary = "DEV ONLY: run the resale listing sweep now",
            description = "Super Admin (`admin.tenants.manage`). Takes listings down that are past 90 days or that a "
                    + "rule says must come down, and returns how many ended each way. Exists only under the dev profile.")
    @PostMapping("/sweep")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<Map<ListingStatus, Integer>> sweep() {
        return ResponseEntity.ok(sweeper.sweep(Instant.now()));
    }
}
