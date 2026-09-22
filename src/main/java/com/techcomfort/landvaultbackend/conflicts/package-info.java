/**
 * Spatial conflict detection — the platform's core anti-fraud capability,
 * and the reason the PostGIS geometry work exists.
 * <p>
 * Detects when two estates' boundaries physically overlap (potentially
 * across companies, which is the duplicate-allocation scam LandVault exists
 * to kill) and when two plots overlap inside one estate (the more common
 * version, undetectable until plots carried geometry). Persists each
 * overlap as a reviewable record, runs the Super Admin review workflow, and
 * answers whether a conflict should block publication.
 * <p>
 * Public surface:
 * {@link com.techcomfort.landvaultbackend.conflicts.ConflictDetectionApi}
 * (called by {@code inventory} when a footprint is written, and by the
 * future publication gate),
 * {@link com.techcomfort.landvaultbackend.conflicts.ConflictPublicationCheck},
 * {@link com.techcomfort.landvaultbackend.conflicts.EstateLabelResolver}
 * (declared here, implemented in {@code inventory} — dependency inversion,
 * because {@code inventory} already depends on this module and the reverse
 * edge would be a cycle), and the DTOs in {@code conflicts.dto}.
 * <p>
 * Two things worth knowing before changing anything here, both in AGENTS.md
 * at length: detection reads and writes through {@code SECURITY DEFINER}
 * functions rather than repositories, because it must cross tenants and
 * because {@code listing_conflicts} is platform-scope only; and the
 * tenant-facing view must never disclose the counterparty's identity.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Conflicts"
)
package com.techcomfort.landvaultbackend.conflicts;
