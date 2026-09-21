package com.techcomfort.landvaultbackend.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One estate with everything hanging off it — blocks, tiers, title and
 * verification checks — assembled in a fixed number of queries regardless of
 * how many of each there are.
 * <p>
 * {@code title} is null when none has been recorded, and
 * {@code verificationChecks} only contains checks somebody actually ran.
 * <strong>An absent check is not a passed one</strong>; nothing here
 * manufactures a {@code not_checked} row to fill the shape out.
 */
public record EstateDetailDto(
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
        List<BlockDto> blocks,
        List<PriceTierDto> priceTiers,
        EstateTitleDto title,
        List<VerificationCheckDto> verificationChecks,
        PlotCountsDto plotCounts,
        Instant createdAt
) {
}
