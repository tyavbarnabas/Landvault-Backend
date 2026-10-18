package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.LetterTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads run under the caller's RLS. */
public interface LetterTemplateRepository extends JpaRepository<LetterTemplate, UUID> {

    List<LetterTemplate> findByTenantIdOrderByVersionDesc(UUID tenantId);

    Optional<LetterTemplate> findFirstByTenantIdAndBranchIdIsNullOrderByVersionDesc(UUID tenantId);

    Optional<LetterTemplate> findFirstByTenantIdAndBranchIdOrderByVersionDesc(UUID tenantId, UUID branchId);

    /** Every company's and branch's newest version that HomeTrue hasn't decided yet — the queue, oldest first. */
    @Query("""
            SELECT t FROM LetterTemplate t
            WHERE t.version = (SELECT MAX(t2.version) FROM LetterTemplate t2
                               WHERE t2.tenantId = t.tenantId
                                 AND ((t2.branchId IS NULL AND t.branchId IS NULL) OR t2.branchId = t.branchId))
              AND NOT EXISTS (SELECT r FROM LetterTemplateReview r WHERE r.templateId = t.id)
            ORDER BY t.createdAt ASC""")
    List<LetterTemplate> findAwaitingReview();
}
