package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Which {@link OrganizationDocument} a {@link VerificationDecision} named as
 * failed — schema existed since slice A (012); this is its first entity.
 * Plain child rows, not append-only itself (there's nothing to correct: a
 * row here either exists or doesn't, tied 1:1 to its immutable parent
 * decision) — no {@code createdAt}/{@code tenantId}, just the two FKs the
 * table's own unique constraint already governs.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "verification_decision_documents",
        indexes = {
                @Index(name = "idx_verification_decision_documents_decision_id", columnList = "decision_id"),
                @Index(name = "idx_verification_decision_documents_document_id", columnList = "document_id")
        }
)
public class VerificationDecisionDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "decision_id", nullable = false)
    private UUID decisionId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;
}
