package com.techcomfort.landvaultbackend.conflicts.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What the affected tenant sees about a conflict on their own estate
 * (CD-11).
 * <p>
 * <strong>This type deliberately has no field capable of holding the other
 * company's identity.</strong> Not "has one that we null out" — has none.
 * A narrow type cannot accidentally widen in a later refactor the way a
 * controller that strips fields from a wide DTO can; someone adding
 * disclosure here has to add a field and would have to mean it.
 * <p>
 * The reason for the rule: both parties will believe they are right.
 * Handing each the other's identity invites direct confrontation — possibly
 * on-site — over disputed land, with the platform having created the
 * introduction. Keeping the platform as intermediary is the safer position.
 * <p>
 * This is the second of two defences, and the weaker one. The load-bearing
 * one is {@code landvault_tenant_estate_conflicts()} (changeset 047), which
 * never selects the counterparty's columns at all, so their identity is
 * not merely unmapped — it never leaves the database.
 * <p>
 * <strong>Tone.</strong> Most conflicts are survey errors, not fraud.
 * {@code guidance} is factual and solution-oriented, and nothing here reads
 * as an accusation: the payload states what was detected, how much land is
 * involved, and what happens next.
 */
public record TenantConflictDto(
        UUID id,
        String conflictType,
        /** The caller's own estate or plot — never the other party's. */
        UUID yourEntityId,
        String yourEntityLabel,
        BigDecimal overlapAreaSqm,
        /** The share of the caller's own entity that overlaps. Their side only. */
        BigDecimal overlapPctOfYours,
        String severity,
        String status,
        boolean blocksPublication,
        /**
         * True once detection noticed the overlap is gone but a Super Admin
         * hasn't reviewed it yet. Distinct from {@code blocksPublication}
         * on purpose: a HIGH conflict can be both — still blocking, and
         * also already corrected and waiting on a human, rather than
         * actively overlapping. {@code guidance} reflects which is true.
         */
        boolean underReview,
        String guidance,
        Instant detectedAt
) {
}
