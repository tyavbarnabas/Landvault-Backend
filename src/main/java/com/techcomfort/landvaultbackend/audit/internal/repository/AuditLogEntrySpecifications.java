package com.techcomfort.landvaultbackend.audit.internal.repository;

import com.techcomfort.landvaultbackend.audit.internal.domain.AuditLogEntry;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit log's combinable filters. Every parameter is nullable; only the
 * ones actually supplied are applied.
 * <p>
 * These match the existing indexes ({@code occurred_at},
 * {@code actor_user_id}, {@code tenant_id}, {@code (target_type, target_id)})
 * — no new index is added here without evidence one is needed.
 */
public final class AuditLogEntrySpecifications {

    private AuditLogEntrySpecifications() {
    }

    public static Specification<AuditLogEntry> matching(
            UUID actorUserId, UUID tenantId, String targetType, UUID targetId,
            String action, Boolean privileged, Instant occurredAfter, Instant occurredBefore) {

        Specification<AuditLogEntry> spec = null;
        spec = and(spec, actorUserId == null ? null : equal("actorUserId", actorUserId));
        spec = and(spec, tenantId == null ? null : equal("tenantId", tenantId));
        spec = and(spec, targetType == null || targetType.isBlank() ? null : equal("targetType", targetType));
        spec = and(spec, targetId == null ? null : equal("targetId", targetId));
        spec = and(spec, action == null || action.isBlank() ? null : equal("action", action));
        spec = and(spec, privileged == null ? null : equal("privileged", privileged));
        // Inclusive at both ends — a reviewer asking for "that day" expects
        // entries on the boundary itself to be in the results.
        spec = and(spec, occurredAfter == null
                ? null
                : (Specification<AuditLogEntry>) (root, cq, cb) ->
                        cb.greaterThanOrEqualTo(root.get("occurredAt"), occurredAfter));
        spec = and(spec, occurredBefore == null
                ? null
                : (Specification<AuditLogEntry>) (root, cq, cb) ->
                        cb.lessThanOrEqualTo(root.get("occurredAt"), occurredBefore));
        return spec;
    }

    private static Specification<AuditLogEntry> equal(String attribute, Object value) {
        return (root, cq, cb) -> cb.equal(root.get(attribute), value);
    }

    private static Specification<AuditLogEntry> and(
            Specification<AuditLogEntry> current, Specification<AuditLogEntry> next) {
        if (next == null) {
            return current;
        }
        return current == null ? next : current.and(next);
    }
}
