package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import com.techcomfort.landvaultbackend.documents.internal.enums.LetterApprovalAction;
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

/** One approval or withdrawal of an estate's allocation letter, with the versions it saw (changeset 093). Never updated. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_letter_approvals")
public class EstateLetterApproval extends AbstractAppendOnlyEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", updatable = false)
    private UUID branchId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 20)
    private LetterApprovalAction action;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "terms_version", updatable = false)
    private Integer termsVersion;

    @Column(name = "company_letterhead_version", updatable = false)
    private Integer companyLetterheadVersion;

    @Column(name = "branch_letterhead_version", updatable = false)
    private Integer branchLetterheadVersion;

    @Column(name = "fees_version", updatable = false)
    private Integer feesVersion;

    @Column(name = "refund_terms_version", updatable = false)
    private Integer refundTermsVersion;

    @Column(name = "default_terms_version", updatable = false)
    private Integer defaultTermsVersion;

    @Column(name = "template_source", updatable = false, length = 10)
    private String templateSource;

    @Column(name = "template_version", updatable = false)
    private Integer templateVersion;
}
