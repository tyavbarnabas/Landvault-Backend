package com.techcomfort.landvaultbackend.tenancy.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches the frontend's {@code SupportAccessGrant} exactly (no
 * {@code grantedToUserId} field — the frontend type doesn't carry one, so
 * this DTO doesn't expose it either, same "don't fabricate a field the
 * frontend doesn't expect" discipline as everywhere else).
 */
public record SupportAccessGrantDto(UUID id, UUID tenantId, String reason, Instant requestedAt, Instant expiresAt) {
}
