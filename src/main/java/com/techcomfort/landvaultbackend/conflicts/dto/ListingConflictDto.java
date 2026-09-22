package com.techcomfort.landvaultbackend.conflicts.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A conflict as the Super Admin review queue sees it (CD-6) — both sides,
 * both companies, the overlap and its share of each.
 * <p>
 * <strong>Platform-scope only.</strong> This shape names both parties, and
 * must never be returned on a tenant-facing route; that surface uses
 * {@link TenantConflictDto}, which cannot carry a counterparty at all.
 * <p>
 * Field names follow the frontend's own {@code ListingConflict} interface
 * ({@code listingConflictsService.ts}) so {@code /admin/listing-conflicts}
 * renders without a translation layer — hence {@code estateA*}/{@code estateB*}
 * rather than the {@code left}/{@code right} the columns use. The two known
 * divergences from that interface are recorded in AGENTS.md:
 * <ul>
 *   <li>For a {@code PLOT_OVERLAP}, {@code estateAId}/{@code estateBId}
 *       carry <em>plot</em> ids and {@code estateId} carries the estate
 *       containing them. The frontend has no plot-conflict concept yet, so
 *       {@code conflictType} is what distinguishes them.</li>
 *   <li>{@code estateAFootprint}/{@code estateBFootprint} are not returned.
 *       The frontend denormalizes those purely for map rendering; nothing
 *       here fabricates them, and serving them would mean this module
 *       reaching into inventory's geometry for a field the queue does not
 *       need to make a decision.</li>
 * </ul>
 */
public record ListingConflictDto(
        UUID id,
        String conflictType,
        UUID estateAId,
        String estateAName,
        UUID tenantAId,
        String tenantAName,
        UUID estateBId,
        String estateBName,
        UUID tenantBId,
        String tenantBName,
        /** The containing estate for a plot conflict; null for an estate conflict. */
        UUID estateId,
        boolean crossTenant,
        String severity,
        BigDecimal overlapAreaSqm,
        BigDecimal overlapPctOfA,
        BigDecimal overlapPctOfB,
        Instant detectedAt,
        String status,
        /**
         * Non-null once detection found the overlap gone, while
         * {@code status} is still live — the geometry cleared but nobody
         * has decided anything yet. A HIGH conflict (or a confirmed
         * duplicate at any severity) does <strong>not</strong> unblock
         * publication on this signal alone; it exists so a Super Admin can
         * find and prioritise exactly the conflicts waiting on a decision.
         * See AGENTS.md.
         */
        Instant geometryClearedAt,
        String reviewedBy,
        Instant reviewedAt,
        String resolutionNote
) {
}
