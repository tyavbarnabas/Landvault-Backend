package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.common.VerificationSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * An estate's land title instrument. One per estate.
 * <p>
 * <strong>Title is per-estate, never per-company.</strong> A developer can
 * hold clean title on one estate and none at all on the next, so this cannot
 * live on {@code Organization} — {@code organization_documents} carries the
 * mirror of this rule, deliberately holding corporate verification documents
 * (CAC, TIN, SCUML) and no land title. The two halves of the rule are meant
 * to be read together; see AGENTS.md.
 * <p>
 * Declared by the developer, verified by HomeTrue: see AGENTS.md, "Estate titles".
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "estate_titles",
        indexes = @Index(name = "idx_estate_titles_tenant_id", columnList = "tenant_id"),
        uniqueConstraints = @UniqueConstraint(name = "uq_estate_titles_estate_id", columnNames = "estate_id")
)
@SQLRestriction("deleted = false")
public class EstateTitle extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_type", nullable = false, length = 40)
    private TitleType titleType;

    @Column(name = "title_number", length = 120)
    private String titleNumber;

    @Column(name = "issued_date")
    private LocalDate issuedDate;

    /** Private stored files ({@code estate_title_documents}); the first two are required at submission. */
    @Column(name = "title_document_file_id")
    private UUID titleDocumentFileId;

    @Column(name = "survey_plan_file_id")
    private UUID surveyPlanFileId;

    @Column(name = "deed_file_id")
    private UUID deedFileId;

    /** Only HomeTrue moves it off {@code PENDING}; every submission puts it back. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TitleStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /** Null for titles recorded before review existed (changeset 087). */
    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source", length = 32)
    private VerificationSource verificationSource;

    /** The reviewer's note. Shown to the developer; required on a rejection. */
    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;
}
