package com.techcomfort.landvaultbackend.conflicts;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Turns estate and plot ids into display labels for the admin review queue.
 * <p>
 * <strong>Declared here and implemented in {@code inventory}, deliberately
 * inverted</strong> — the same technique as {@code audit}'s
 * {@link com.techcomfort.landvaultbackend.audit.ActorNameResolver}, and the
 * fourth time this codebase has needed it. {@code inventory} already depends
 * on {@code conflicts} (it triggers detection when a footprint is written),
 * so {@code conflicts} calling an {@code InventoryApi} back would close a
 * module cycle. Owning the interface here keeps every arrow pointing the way
 * it already does.
 * <p>
 * Batched by construction: both methods take a collection, so one page of
 * conflicts resolves its labels in two queries rather than one per row.
 * <p>
 * Note this resolver is used <strong>only</strong> by the platform-scope
 * admin queue. The tenant-facing view never calls it — it must not learn
 * anything about the other side, including an estate name, which usually
 * identifies the company as plainly as its own name would.
 */
public interface EstateLabelResolver {

    /**
     * Estate names by id. Ids with no match are absent rather than an error:
     * a conflict record outlives a soft-deleted estate by design, since the
     * history is kept.
     */
    Map<UUID, String> estateNamesFor(Collection<UUID> estateIds);

    /**
     * Plot labels by id — "Block A / Plot 12", or just the plot number when
     * the plot has no block. Same absent-not-error rule as above.
     */
    Map<UUID, String> plotLabelsFor(Collection<UUID> plotIds);
}
