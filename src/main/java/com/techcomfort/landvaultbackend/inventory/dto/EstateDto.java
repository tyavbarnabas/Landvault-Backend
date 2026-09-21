package com.techcomfort.landvaultbackend.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An estate as returned by creation. Enum-typed fields are wire-value
 * strings, not the internal enum types — a public DTO must not expose an
 * internal type through its own signature (AGENTS.md).
 * <p>
 * {@code footprintAreaSqm} is computed via the geography cast, so it is
 * genuine square metres. Null when the estate has no boundary yet.
 */
public record EstateDto(
        UUID id,
        UUID tenantId,
        UUID branchId,
        String name,
        String slug,
        String description,
        String area,
        String city,
        String state,
        String address,
        BigDecimal cornerPremiumPct,
        String intent,
        List<String> amenities,
        boolean published,
        Instant publishedAt,
        boolean hasFootprint,
        BigDecimal footprintAreaSqm,
        Instant createdAt
) {
}
