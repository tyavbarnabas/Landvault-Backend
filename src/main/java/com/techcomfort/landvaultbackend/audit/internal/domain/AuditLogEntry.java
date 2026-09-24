package com.techcomfort.landvaultbackend.audit.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * One audited action — appended, never edited, same
 * {@link AbstractAppendOnlyEntity} convention as {@code VerificationDecision}
 * (no {@code updatedAt}, no {@code deleted}: a correction is a new row, not
 * an edit to this one). Written only through {@code AuditApi.record(...)}
 * — see that interface and AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "audit_log_entries",
        indexes = {
                @Index(name = "idx_audit_log_entries_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_audit_log_entries_actor_user_id", columnList = "actor_user_id"),
                @Index(name = "idx_audit_log_entries_target", columnList = "target_type, target_id"),
                @Index(name = "idx_audit_log_entries_occurred_at", columnList = "occurred_at")
        }
)
public class AuditLogEntry extends AbstractAppendOnlyEntity {

    /**
     * Null when the platform itself acted — a scheduled job, with no human
     * behind it. Every entry had an actor until the reservation expiry sweep
     * arrived; see changeset 055 for why a sentinel user was rejected.
     */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    // A string code, not an enum — every module that will eventually write
    // here shouldn't need to modify a shared enum to add a new action.
    // e.g. "tenant.created", "tenant.verification_decision".
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "target_type", nullable = false, length = 100)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    // Nullable — a platform-level action (no tenant involved) has none.
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "detail")
    private String detail;

    // For support-access entries in a later slice — visual distinction
    // only, carries no authorization meaning of its own. See AGENTS.md's
    // "support access is visibility, not authority" note.
    @Column(name = "privileged", nullable = false)
    private Boolean privileged;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
