package com.techcomfort.landvaultbackend.conflicts;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One conflict as the owning company may see it — its own side only, never
 * the other party (CD-11). The public twin of the tenant view, for other
 * modules reporting what a boundary change did.
 */
public record ConflictItem(
        UUID id,
        String conflictType,
        UUID yourEntityId,
        String yourEntityLabel,
        BigDecimal overlapAreaSqm,
        String severity,
        String status,
        boolean live,
        boolean blocksPublication,
        boolean underReview,
        String guidance
) {
}
