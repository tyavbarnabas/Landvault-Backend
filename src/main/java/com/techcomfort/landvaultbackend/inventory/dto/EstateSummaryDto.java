package com.techcomfort.landvaultbackend.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A list row. Deliberately slimmer than {@link EstateDetailDto} — no
 * blocks, tiers, title or checks, which a directory never renders and which
 * would cost a query per row to load.
 * <p>
 * {@code footprintAreaSqm} and the plot counts are both filled by one
 * grouped query for the whole page, never one per row.
 */
public record EstateSummaryDto(
        UUID id,
        UUID branchId,
        String name,
        String slug,
        String area,
        String city,
        String state,
        String intent,
        boolean published,
        Instant publishedAt,
        boolean hasFootprint,
        BigDecimal footprintAreaSqm,
        PlotCountsDto plotCounts,
        Instant createdAt
) {
}
