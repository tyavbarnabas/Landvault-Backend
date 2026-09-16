package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationDecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A single reviewer decision on an {@link Organization}'s verification —
 * appended, never edited. REQUEST_MORE_INFO doesn't transition
 * verification_state — see AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "verification_decisions",
        indexes = {
                @Index(name = "idx_verification_decisions_organization_id", columnList = "organization_id"),
                @Index(name = "idx_verification_decisions_reviewer_user_id", columnList = "reviewer_user_id")
        }
)
public class VerificationDecision extends AbstractAppendOnlyEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "reviewer_user_id", nullable = false)
    private UUID reviewerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 32)
    private VerificationDecisionType decision;

    // Nullable for approvals; required in practice for REJECTED and
    // REQUEST_MORE_INFO — enforced in the service layer, not a DB
    // constraint, since the rule is behavioural (which decision requires
    // it), not a fixed column property.
    @Column(name = "reason")
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}
