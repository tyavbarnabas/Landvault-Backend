package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTerms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads run under the caller's RLS. */
public interface EstateLetterTermsRepository extends JpaRepository<EstateLetterTerms, UUID> {

    Optional<EstateLetterTerms> findFirstByEstateIdOrderByVersionDesc(UUID estateId);

    List<EstateLetterTerms> findByEstateIdOrderByVersionDesc(UUID estateId);

    /** Every estate's newest version that HomeTrue hasn't decided yet — the review queue, oldest first. */
    @Query("""
            SELECT t FROM EstateLetterTerms t
            WHERE t.version = (SELECT MAX(t2.version) FROM EstateLetterTerms t2 WHERE t2.estateId = t.estateId)
              AND NOT EXISTS (SELECT r FROM EstateLetterTermsReview r WHERE r.termsId = t.id)
            ORDER BY t.createdAt ASC""")
    List<EstateLetterTerms> findAwaitingReview();
}
