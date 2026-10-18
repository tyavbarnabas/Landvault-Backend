package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.LetterTemplateReview;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads run under the caller's RLS: a developer reads decisions on their own templates; only platform scope writes. */
public interface LetterTemplateReviewRepository extends JpaRepository<LetterTemplateReview, UUID> {

    Optional<LetterTemplateReview> findByTemplateId(UUID templateId);

    List<LetterTemplateReview> findByTemplateIdIn(Collection<UUID> templateIds);

    /** The company's template letters use: its newest approved version. */
    Optional<LetterTemplateReview> findFirstByTenantIdAndBranchIdIsNullAndDecisionOrderByTemplateVersionDesc(
            UUID tenantId, ReviewDecision decision);

    /** A branch's own approved template, which replaces the company's for its estates. */
    Optional<LetterTemplateReview> findFirstByTenantIdAndBranchIdAndDecisionOrderByTemplateVersionDesc(
            UUID tenantId, UUID branchId, ReviewDecision decision);

    List<LetterTemplateReview> findByDecisionOrderByCreatedAtDesc(ReviewDecision decision);
}
