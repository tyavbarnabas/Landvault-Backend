package com.techcomfort.landvaultbackend.kyc.internal.repository;

import com.techcomfort.landvaultbackend.kyc.internal.domain.KycRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Reached only by {@code userId} — never enumerated across buyers, which is
 * why {@code kyc_records} carries no RLS policy (see changeset 051).
 */
public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {

    Optional<KycRecord> findByUserId(UUID userId);
}
