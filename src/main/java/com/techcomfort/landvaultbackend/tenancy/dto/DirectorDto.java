package com.techcomfort.landvaultbackend.tenancy.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code idNumber}/{@code bvn} are NDPR-regulated and NEVER returned in
 * full here — masked to the last 4 characters, matching how the frontend
 * already displays BVN. A reveal action needs its own endpoint with its
 * own access logging (AGENTS.md already requires every read of these to be
 * logged) — not built in this slice. See {@code TenantMapper#mask}.
 */
public record DirectorDto(
        UUID id,
        String fullName,
        String role,
        String nationality,
        String idType,
        String idNumber,
        String bvn,
        BigDecimal ownershipPct,
        boolean isBeneficialOwner
) {
}
