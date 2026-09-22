package com.techcomfort.landvaultbackend.marketplace;

/**
 * Where one estate stands against the marketplace conditions, straight from
 * {@code marketplace_estate_eligibility}.
 * <p>
 * The tenant facts are separate booleans on purpose. Verification and portal
 * status are different axes (AGENTS.md: never reason about them as one
 * concept), and the publish endpoint has to name the specific one that
 * failed (PB-3). {@code eligible} folds in all five, including the conflict
 * check, and is exactly what the public read filters on.
 */
public record EstateEligibility(
        boolean published,
        boolean tenantVerified,
        boolean tenantEntitled,
        boolean tenantActive,
        boolean eligible
) {
}
