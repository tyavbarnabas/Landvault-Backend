package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Reads run under the caller's RLS. */
public interface EstateLetterApprovalRepository extends JpaRepository<EstateLetterApproval, UUID> {

    /** The estate's latest approval or withdrawal: what stands now, if its versions are still current. */
    Optional<EstateLetterApproval> findFirstByEstateIdOrderByCreatedAtDesc(UUID estateId);
}
