package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.conflicts.ConflictChanges;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One boundary correction. The detection fields are filled only on the
 * response that actually applied the boundary; elsewhere they are null.
 * Never names another company (CD-11).
 */
public record BoundaryChangeDto(
        UUID id,
        UUID estateId,
        String estateName,
        @Schema(description = "Reviewer views only: the developer's company.") String companyName,
        @Schema(description = "applied, pending, approved, rejected or withdrawn") String status,
        BigDecimal previousAreaSqm,
        BigDecimal proposedAreaSqm,
        @Schema(description = "Land added plus land removed, in square metres.") BigDecimal changedAreaSqm,
        @Schema(description = "changedAreaSqm as a percentage of the previous area.") BigDecimal changedPct,
        String reason,
        UUID requestedBy,
        Instant createdAt,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote,
        GeoJsonPolygonDto previousFootprint,
        GeoJsonPolygonDto proposedFootprint,
        @Schema(description = "Set when this response applied the boundary: true if it now overlaps another "
                + "company's land, which keeps the estate off the marketplace.")
        Boolean publicationBlocked,
        String blockReason,
        Integer warningConflictCount,
        @Schema(description = "Set when this response applied the boundary: which conflicts this change raised, resolved, left awaiting review, or left "
                + "open — your own side only; the other party is never named.")
        ConflictChanges conflictChanges
) {
}
