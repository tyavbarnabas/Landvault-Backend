package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTermsReview;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads run under the caller's RLS: a developer reads decisions on their own estates; only platform scope writes. */
public interface EstateLetterTermsReviewRepository extends JpaRepository<EstateLetterTermsReview, UUID> {

    Optional<EstateLetterTermsReview> findByTermsId(UUID termsId);

    List<EstateLetterTermsReview> findByTermsIdIn(Collection<UUID> termsIds);

    List<EstateLetterTermsReview> findByEstateId(UUID estateId);

    /** The version letters use: the newest one HomeTrue approved. */
    Optional<EstateLetterTermsReview> findFirstByEstateIdAndDecisionOrderByTermsVersionDesc(
            UUID estateId, ReviewDecision decision);

    List<EstateLetterTermsReview> findByDecisionOrderByCreatedAtDesc(ReviewDecision decision);
}
