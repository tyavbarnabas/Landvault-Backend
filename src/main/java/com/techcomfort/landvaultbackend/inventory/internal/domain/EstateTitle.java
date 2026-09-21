package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TitleType;
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

    /**
     * References into the documents module, stored as opaque ids — inventory
     * does not resolve them, and must not reach into that module to do so.
     * Nullable: title can be recorded before the scans are uploaded.
     */
    @Column(name = "survey_plan_document_id")
    private UUID surveyPlanDocumentId;

    @Column(name = "deed_document_id")
    private UUID deedDocumentId;
}
