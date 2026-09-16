package com.techcomfort.landvaultbackend.tenancy.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code type}/{@code status} are the enums' wire values — see AGENTS.md. */
public record TenantDocumentDto(
        UUID id,
        String type,
        String fileName,
        long size,
        String status,
        String rejectionReason,
        Instant uploadedAt
) {
}
