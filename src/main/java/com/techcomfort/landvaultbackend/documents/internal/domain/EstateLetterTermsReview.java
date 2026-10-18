package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
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

import java.util.UUID;

/** HomeTrue's decision on one version of an estate's allocation conditions (changeset 092). Never updated. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_letter_terms_reviews")
public class EstateLetterTermsReview extends AbstractAppendOnlyEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", updatable = false)
    private UUID branchId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "terms_id", nullable = false, updatable = false)
    private UUID termsId;

    @Column(name = "terms_version", nullable = false, updatable = false)
    private int termsVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 20)
    private ReviewDecision decision;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "reviewer_user_id", nullable = false, updatable = false)
    private UUID reviewerUserId;
}
