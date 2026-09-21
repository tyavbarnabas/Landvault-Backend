package com.techcomfort.landvaultbackend.inventory.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code status} of {@code not_checked} means nobody has looked — it is not a
 * clean bill of health and must never render as positive.
 */
public record VerificationCheckDto(
        UUID id,
        UUID estateId,
        String checkType,
        String status,
        String verificationSource,
        Instant lastVerifiedAt,
        String notes
) {
}
