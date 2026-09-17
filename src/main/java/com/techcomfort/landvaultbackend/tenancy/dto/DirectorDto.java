package com.techcomfort.landvaultbackend.tenancy.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code idNumber} is NDPR-regulated and NEVER returned in full here —
 * masked to the last 4 characters. A reveal action needs its own endpoint
 * with its own access logging (AGENTS.md already requires every read of it
 * to be logged) — not built in this slice. See {@code TenantMapper#mask}.
 * <p>
 * No {@code bvn} field — it was collected once and removed entirely
 * (backend changeset 024, frontend in the same slice): it was never
 * verified against anything, so it was liability without benefit. See
 * AGENTS.md.
 */
public record DirectorDto(
        UUID id,
        String fullName,
        String role,
        String nationality,
        String idType,
        String idNumber,
        BigDecimal ownershipPct,
        boolean isBeneficialOwner
) {
}
