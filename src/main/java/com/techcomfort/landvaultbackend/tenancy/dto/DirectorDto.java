package com.techcomfort.landvaultbackend.tenancy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "Director", description = "A company director. Personal data: see `idNumber`.")
public record DirectorDto(
        UUID id,
        String fullName,
        String role,
        String nationality,
        String idType,
        @Schema(description = "**Masked to the last four characters, always** — NDPR-regulated "
                + "personal data that is never returned in full by this API. Revealing it would "
                + "need its own endpoint with its own access logging.",
                example = "******8901")
        String idNumber,
        BigDecimal ownershipPct,
        @Schema(description = "A compliance assertion made at a point in time, stored rather than "
                + "recomputed from `ownershipPct` — recomputing it live would silently rewrite who "
                + "was flagged when, as ownership changes. The threshold is 25%.")
        boolean isBeneficialOwner
) {
}
