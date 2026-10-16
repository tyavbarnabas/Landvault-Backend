package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstateTitleRepository extends JpaRepository<EstateTitle, UUID> {

    // 1:1 with the estate — a second title row would make "the" title ambiguous.
    Optional<EstateTitle> findByEstateId(UUID estateId);

    /** A review decision and a resubmission of the same title take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM EstateTitle t WHERE t.id = :id")
    Optional<EstateTitle> findByIdForUpdate(UUID id);

    List<EstateTitle> findByStatusOrderBySubmittedAtAsc(TitleStatus status);

    List<EstateTitle> findByStatusOrderByDecidedAtDesc(TitleStatus status);
}
