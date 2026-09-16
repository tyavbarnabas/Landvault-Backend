package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.VerificationDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VerificationDecisionRepository extends JpaRepository<VerificationDecision, UUID> {

    // Oldest first — an append-only audit trail reads naturally in the
    // order it happened, not reverse-chronological.
    List<VerificationDecision> findByOrganizationIdOrderByDecidedAtAsc(UUID organizationId);
}
