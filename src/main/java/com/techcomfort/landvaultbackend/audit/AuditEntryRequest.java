package com.techcomfort.landvaultbackend.audit;

import java.util.UUID;

/**
 * What {@link AuditApi#record} takes — a plain request shape rather than
 * seven positional parameters. {@code tenantId} and {@code detail} are the
 * only nullable fields: a platform-level action has no tenant, and not
 * every action needs free-text detail.
 */
public record AuditEntryRequest(
        UUID actorUserId,
        String action,
        String targetType,
        UUID targetId,
        UUID tenantId,
        String detail,
        boolean privileged
) {

    /** The common case — a non-privileged, tenant-scoped action with a detail message. */
    public static AuditEntryRequest of(UUID actorUserId, String action, String targetType, UUID targetId, UUID tenantId, String detail) {
        return new AuditEntryRequest(actorUserId, action, targetType, targetId, tenantId, detail, false);
    }
}
