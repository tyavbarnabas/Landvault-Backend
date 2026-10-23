package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 5b: a developer's revocation of an instalment plan (changeset 107) —
 * the notice, then how it ended. Buyer-owned like the purchase: not
 * RLS-policied, reached by transaction after the seller and plot checks.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "plan_revocations")
@SQLRestriction("deleted = false")
public class PlanRevocation extends AbstractEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RevocationStatus status;

    /** The developer's reason, shown to the buyer. */
    @Column(name = "reason", nullable = false, updatable = false, length = 500)
    private String reason;

    /** The purchase's terms when notice was given. */
    @Column(name = "revocation_after_months", nullable = false, updatable = false)
    private Integer revocationAfterMonths;

    @Column(name = "notice_days", nullable = false, updatable = false)
    private Integer noticeDays;

    /** The oldest instalment that met the trigger when notice was given. */
    @Column(name = "instalment_sequence", nullable = false, updatable = false)
    private Integer instalmentSequence;

    @Column(name = "instalment_due_date", nullable = false, updatable = false)
    private LocalDate instalmentDueDate;

    @Column(name = "notice_given_at", nullable = false, updatable = false)
    private Instant noticeGivenAt;

    /** The last day (Lagos) the buyer may pay what is overdue; confirmable from the next day. */
    @Column(name = "deadline", nullable = false, updatable = false)
    private LocalDate deadline;

    @Column(name = "started_by", nullable = false, updatable = false)
    private UUID startedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Null when the system closed it (the buyer caught up, or withdrew). */
    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "close_note", length = 500)
    private String closeNote;

    public boolean isOpen() {
        return status == RevocationStatus.NOTICE;
    }
}
