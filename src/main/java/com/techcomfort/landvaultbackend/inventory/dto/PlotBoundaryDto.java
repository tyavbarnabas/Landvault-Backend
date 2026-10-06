package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.conflicts.ConflictChanges;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The result of correcting a plot's boundary (IE-9): the recomputed surveyed
 * area, and what plot overlap detection found before and after — so a
 * correction that clears a conflict, or creates one, is reported rather than
 * left for the developer to discover.
 */
public record PlotBoundaryDto(
        UUID plotId,

        @Schema(description = "Surveyed area before the correction, in square metres. Null if the plot had no boundary.")
        BigDecimal previousActualAreaSqm,

        @Schema(description = "Surveyed area now, in square metres, computed from the new boundary.")
        BigDecimal actualAreaSqm,

        @Schema(description = "Overlapping plot pairs in this estate before the correction.")
        int plotOverlapsInEstateBefore,

        @Schema(description = "Overlapping plot pairs in this estate now. A pair that no longer overlaps "
                + "resolves on its own; a new one is recorded for review.")
        int plotOverlapsInEstateAfter,
        @Schema(description = "Which conflicts this change raised, resolved, left awaiting review, or left "
                + "open — your own side only; the other party is never named.")
        ConflictChanges conflictChanges
) {
}
