package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of a status change (IE-7/IE-8): skip and report, never all or
 * nothing — one reserved plot must not block a 200-plot launch.
 */
public record PlotStatusChangeDto(
        @Schema(description = "The status asked for.")
        String status,
        int requested,
        @Schema(description = "Plots that changed (or, on a dry run, would change).")
        List<UUID> changed,
        @Schema(description = "Plots left as they were, each with the reason.")
        List<Skipped> skipped,
        boolean dryRun
) {

    public record Skipped(UUID plotId, String plotNumber, String currentStatus, String code, String reason) {
    }
}
