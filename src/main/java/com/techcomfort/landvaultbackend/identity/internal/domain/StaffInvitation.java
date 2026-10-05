package com.techcomfort.landvaultbackend.identity.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation for one person to join a tenant in one role (SI-1..SI-7,
 * changeset 067). {@code tenantId} is the inviting company. Only the token's
 * hash is stored; company, branch and role names are a snapshot so the
 * accept page can show them before any tenant scope exists. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "staff_invitations")
@SQLRestriction("deleted = false")
public class StaffInvitation extends AbstractEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "scoped_branch_id")
    private UUID scopedBranchId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    // Never the raw token. Null while a branch request awaits approval — no link exists yet.
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "send_count", nullable = false)
    private Integer sendCount;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    /** A branch manager's request (changeset 068): {@code invitedBy} is the requester. */
    @Builder.Default
    @Column(name = "approval_required", nullable = false)
    private boolean approvalRequired = false;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    public boolean isOpen() {
        return acceptedAt == null && revokedAt == null && rejectedAt == null;
    }

    public boolean isAwaitingApproval() {
        return approvalRequired && approvedAt == null && isOpen();
    }

    public boolean isUsableAt(Instant now) {
        return isOpen() && tokenHash != null && expiresAt.isAfter(now);
    }
}
