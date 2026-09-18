package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.VerificationDecisionDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VerificationDecisionDocumentRepository extends JpaRepository<VerificationDecisionDocument, UUID> {

    List<VerificationDecisionDocument> findByDecisionId(UUID decisionId);
}
