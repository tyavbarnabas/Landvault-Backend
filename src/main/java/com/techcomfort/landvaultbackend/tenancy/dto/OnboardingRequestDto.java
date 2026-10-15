package com.techcomfort.landvaultbackend.tenancy.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A company's onboarding request, as a Super Admin sees it. {@code status} is
 * {@code new}, {@code contacted}, {@code converted} or {@code declined};
 * {@code organizationId} is the tenant it became, once converted.
 * {@code needsAttention} is computed when read; {@code attentionReason} is
 * {@code no_contact_yet} (still {@code new} after the configured working days)
 * or null.
 */
public record OnboardingRequestDto(
        UUID id,
        String status,
        String companyName,
        String rcNumber,
        String companyAddress,
        List<String> statesOfOperation,
        Integer estimatedEstateCount,
        String contactName,
        String contactRole,
        String contactEmail,
        String contactPhone,
        String preferredContactMethod,
        String preferredContactTime,
        String message,
        Instant createdAt,
        Instant contactedAt,
        UUID contactedBy,
        String contactNote,
        Instant decidedAt,
        UUID decidedBy,
        String declineReason,
        UUID organizationId,
        boolean needsAttention,
        String attentionReason
) {
}
