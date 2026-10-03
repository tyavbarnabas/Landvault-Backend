package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** {@code PUT .../plots/{plotId}/status} body (IE-7). */
public record ChangePlotStatusRequest(
        @Schema(description = "`withheld`; `available` (back to the variant it had before being withheld); "
                + "or `available-dev` / `available-inv` explicitly. Never `reserved` or `sold` — only checkout "
                + "reaches those.", example = "withheld")
        @NotBlank String status,
        @Schema(description = "Why — recorded in the audit log. A survey dispute, a staff allocation…")
        String reason
) {
}
