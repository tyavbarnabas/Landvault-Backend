package com.techcomfort.landvaultbackend.conflicts;

import java.util.UUID;

/**
 * The conflicts module's public surface for other modules. {@code inventory}
 * calls this when a footprint is written (CD-4), and the future
 * {@code marketplace} publication gate will call
 * {@link #publicationCheckFor(UUID)} (CD-10).
 * <p>
 * Nothing here exposes the {@code ListingConflict} entity or its repository
 * — same boundary rule as every other module (AGENTS.md).
 */
public interface ConflictDetectionApi {

    /**
     * Re-runs cross-estate detection for one estate (CD-1), against every
     * other estate including unpublished drafts (CD-5). Returns how many
     * live conflicts this estate now has recorded.
     * <p>
     * Also performs the CD-9 auto-resolution pass for this estate in the
     * same statement: a live conflict whose overlap no longer exists is
     * marked {@code AUTO_RESOLVED} rather than left to rot in the queue.
     * An in-flight {@code INVESTIGATING} conflict that still overlaps keeps
     * its status (CD-8).
     * <p>
     * Safe to call with an estate that has no footprint — a draft with no
     * boundary simply has nothing to intersect, and the call is a no-op.
     */
    int detectForEstateBoundary(UUID estateId);

    /**
     * Re-runs within-estate plot detection (CD-2) across every plot in the
     * estate that has a footprint. Always same-tenant, therefore always
     * {@code MEDIUM} severity.
     */
    int detectForEstatePlots(UUID estateId);

    /**
     * Whether an estate may be published, as far as conflicts are concerned
     * (CD-10) — the <strong>fifth</strong> condition alongside the four
     * already documented in AGENTS.md, not a replacement for them.
     */
    ConflictPublicationCheck publicationCheckFor(UUID estateId);
}
