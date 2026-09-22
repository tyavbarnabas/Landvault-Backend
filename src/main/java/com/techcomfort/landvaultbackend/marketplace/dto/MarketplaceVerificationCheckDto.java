package com.techcomfort.landvaultbackend.marketplace.dto;

import java.time.Instant;

/**
 * One due-diligence check, with its source (MP-5).
 * <p>
 * {@code verificationSource} is what separates a registry lookup from a
 * person reading a PDF, and a buyer deciding whether to part with money
 * should see which. A check type with no entry here was never checked; the
 * client must render that as unchecked, never as verified.
 */
public record MarketplaceVerificationCheckDto(
        String checkType,
        String status,
        String verificationSource,
        Instant lastVerifiedAt
) {
}
