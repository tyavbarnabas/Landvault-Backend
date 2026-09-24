package com.techcomfort.landvaultbackend.kyc.internal.repository;

import com.techcomfort.landvaultbackend.kyc.internal.domain.KycDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    List<KycDocument> findByKycRecordId(UUID kycRecordId);
}
