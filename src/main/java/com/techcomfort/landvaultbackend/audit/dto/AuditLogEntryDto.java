package com.techcomfort.landvaultbackend.audit.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit entry as the Super Admin console reads it.
 * <p>
 * {@code actorName} is resolved per page via {@code ActorNameResolver} — raw
 * UUIDs are unreadable in an activity stream. It falls back to
 * {@code "Unknown user"} when the account no longer exists, which is an
 * ordinary case: entries outlive the accounts that created them, by design.
 * <p>
 * {@code targetId} is deliberately <strong>not</strong> resolved to a name.
 * Targets span organizations, users and documents across several modules, so
 * doing it properly needs a lookup strategy per type; the frontend has
 * {@code targetType} and {@code targetId} and can link. Possible follow-up,
 * not a gap being hidden.
 * <p>
 * {@code privileged} is carried through verbatim — support-access grants are
 * flagged so a reviewer can see when a platform operator looked into a
 * tenant's data and why. Flattening it here would make the flag pointless.
 */
public record AuditLogEntryDto(
        UUID id,
        UUID actorUserId,
        String actorName,
        String action,
        String targetType,
        UUID targetId,
        UUID tenantId,
        String detail,
        boolean privileged,
        Instant occurredAt
) {
}
