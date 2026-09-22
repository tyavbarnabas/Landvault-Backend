package com.techcomfort.landvaultbackend.conflicts.internal.repository;

import com.techcomfort.landvaultbackend.conflicts.internal.domain.ListingConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Reads and status writes for the <strong>platform-scope</strong> surfaces
 * only — the admin queue and the review transitions.
 * <p>
 * Detection does not go through here. {@code listing_conflicts} carries a
 * platform-scope-only RLS policy (changeset 046) and detection runs inside
 * a tenant's own request, so every statement this repository issues would
 * be filtered to nothing there. Detection uses the {@code SECURITY DEFINER}
 * functions in changeset 047 instead; see {@code ConflictDetectionService}.
 */
public interface ListingConflictRepository
        extends JpaRepository<ListingConflict, UUID>, JpaSpecificationExecutor<ListingConflict> {
}
