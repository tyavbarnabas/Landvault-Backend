package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.OtpCode;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    // At most one row should ever match (a new code supersedes any
    // outstanding one), but ordering makes that explicit rather than
    // assumed.
    Optional<OtpCode> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId, OtpPurpose purpose);

    List<OtpCode> findByUserIdAndPurposeAndConsumedAtIsNull(UUID userId, OtpPurpose purpose);

    // Rate limiting counts codes *generated* in the window, so superseded
    // and failed ones still count against the cap — otherwise requesting
    // repeatedly would reset the limit each time.
    long countByUserIdAndPurposeAndCreatedAtAfter(UUID userId, OtpPurpose purpose, Instant createdAfter);
}
