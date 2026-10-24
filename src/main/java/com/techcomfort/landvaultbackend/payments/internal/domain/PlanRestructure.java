package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.payments.internal.enums.RestructureStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 5c: a buyer's request to restructure their instalment plan, the
 * developer's proposal and how it ended (changeset 108). Buyer-owned like the
 * purchase: not RLS-policied, reached by the buyer, or by the seller after the
 * plot-visibility check. The proposal's new instalments are in
 * {@code plan_restructure_instalments}.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "plan_restructures")
@SQLRestriction("deleted = false")
public class PlanRestructure extends AbstractEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RestructureStatus status;

    /** The buyer's words, shown to the developer. */
    @Column(name = "request_reason", nullable = false, updatable = false, length = 500)
    private String requestReason;

    /** How many instalments the buyer would like the rest spread over, if they said. */
    @Column(name = "preferred_instalments", updatable = false)
    private Integer preferredInstalments;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    // --- the proposal: fixed when proposed, applied unchanged if accepted ---

    @Column(name = "proposed_by")
    private UUID proposedBy;

    @Column(name = "proposed_at")
    private Instant proposedAt;

    /** The schedule version the proposal was worked out on; accepting needs it still in force. */
    @Column(name = "from_schedule_version")
    private Integer fromScheduleVersion;

    @Column(name = "instalment_count")
    private Integer instalmentCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", length = 16)
    private PaymentFrequency frequency;

    @Column(name = "first_due_date")
    private LocalDate firstDueDate;

    /** What was still owed, penalties aside — unchanged by the restructure. */
    @Column(name = "balance", precision = 19, scale = 2)
    private BigDecimal balance;

    /** Penalties reached by then, kept and charged with the first new instalment. */
    @Column(name = "carried_penalty", precision = 19, scale = 2)
    private BigDecimal carriedPenalty;

    /** The last day (Lagos) the buyer may accept. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "letter_id")
    private UUID letterId;

    @Column(name = "letter_number", length = 32)
    private String letterNumber;

    /** Labels captured when proposed (the buyer's later reads have no tenant scope to fetch them). */
    @Column(name = "transaction_reference", length = 32)
    private String transactionReference;

    @Column(name = "plan_name", length = 120)
    private String planName;

    @Column(name = "plot_label")
    private String plotLabel;

    @Column(name = "estate_name")
    private String estateName;

    @Column(name = "lapse_reminder_sent_at")
    private Instant lapseReminderSentAt;

    // --- accepted ---

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_agent")
    private String acceptedUserAgent;

    @Column(name = "to_schedule_version")
    private Integer toScheduleVersion;

    // --- closed ---

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Null when the buyer or the system closed it. */
    @Column(name = "closed_by")
    private UUID closedBy;

    /** The developer's reason for a rejection, or why it closed. */
    @Column(name = "close_note", length = 500)
    private String closeNote;

    public boolean isOpen() {
        return RestructureStatus.OPEN.contains(status);
    }

    public void close(RestructureStatus to, UUID by, String note) {
        status = to;
        closedAt = Instant.now();
        closedBy = by;
        closeNote = note;
    }
}
