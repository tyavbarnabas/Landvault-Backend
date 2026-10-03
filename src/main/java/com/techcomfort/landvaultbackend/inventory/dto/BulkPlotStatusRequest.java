package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** {@code POST .../plots/status} body (IE-8). */
public record BulkPlotStatusRequest(
        @NotEmpty @Size(max = 500) List<UUID> plotIds,
        @Schema(description = "Same values as the single-plot route.", example = "available")
        @NotBlank String status,
        String reason,
        @Schema(description = "True: report what would change, change nothing. The real request re-checks "
                + "every plot regardless — a dry run is not a reservation.")
        Boolean dryRun
) {
}
