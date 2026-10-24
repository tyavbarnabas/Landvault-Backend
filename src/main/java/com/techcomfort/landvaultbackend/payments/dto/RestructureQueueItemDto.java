package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Phase 5c: one open restructure in the developer's queue. */
@Schema(name = "RestructureQueueItem")
public record RestructureQueueItemDto(
        UUID restructureId,
        UUID transactionId,
        String transactionReference,
        String estateName,
        @Schema(example = "Block A, Plot 7") String plotLabel,
        String buyerEmail,
        String planName,
        @Schema(description = "`requested` (yours to decide) or `proposed` (waiting for the buyer).") String status,
        String reason,
        @Schema(nullable = true) Integer preferredInstalments,
        Instant requestedAt,
        @Schema(description = "A proposal's last day for the buyer to accept.", nullable = true) LocalDate expiresOn
) {
}
