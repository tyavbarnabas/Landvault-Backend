package com.techcomfort.landvaultbackend.marketplace;

import java.util.Optional;
import java.util.UUID;

/**
 * The marketplace module's public surface for other modules.
 * <p>
 * Exists so the publish endpoint ({@code inventory}) checks the tenant
 * conditions with the same SQL the public feed filters on. Two
 * implementations of one gate, one in Java and one in a view, would drift;
 * this way the refusal a developer sees and what a buyer sees are the same
 * answer.
 */
public interface MarketplaceApi {

    /**
     * Empty when the estate doesn't exist or is deleted. The caller is
     * expected to have already confirmed the estate is theirs: this reads
     * through a view that bypasses row-level security, so it must never be
     * handed an id the caller hasn't been authorised for.
     */
    Optional<EstateEligibility> eligibilityOf(UUID estateId);

    /**
     * Which estate a plot belongs to, read through the public projection.
     * <p>
     * Exists because {@code checkout} cannot answer it any other way: a
     * buyer's session has no tenant scope, so reading {@code plots} through
     * a repository returns nothing. This reads {@code marketplace_plots},
     * which is owner-privileged and therefore works for an anonymous or
     * buyer-scoped caller.
     * <p>
     * <strong>Empty covers several cases on purpose</strong> — no such plot,
     * a deleted one, or one whose estate is not currently eligible. The
     * caller reports them identically: distinguishing them would let anyone
     * probe which plot ids exist on estates they cannot see, and which
     * companies are suspended.
     */
    Optional<UUID> estateIdOfPlot(UUID plotId);
}
