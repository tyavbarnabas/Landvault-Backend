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
}
