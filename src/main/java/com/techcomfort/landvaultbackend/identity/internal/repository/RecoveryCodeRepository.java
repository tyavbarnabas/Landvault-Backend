package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.RecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {

    List<RecoveryCode> findByUserIdAndConsumedAtIsNull(UUID userId);

    long countByUserIdAndConsumedAtIsNull(UUID userId);
}
