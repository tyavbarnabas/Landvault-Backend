package com.techcomfort.landvaultbackend.storage.internal.repository;

import com.techcomfort.landvaultbackend.storage.internal.domain.StoredFile;
import com.techcomfort.landvaultbackend.storage.internal.enums.StoredFileStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Not RLS-policied: buyers upload too and have no tenant (same reasoning as
 * kyc_records). A file is reached by its id, and acted on for a person only
 * when they uploaded it; the owning module decides everything else.
 */
public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM StoredFile f WHERE f.id = :id")
    Optional<StoredFile> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT f.id FROM StoredFile f WHERE f.status = :status AND f.createdAt < :before ORDER BY f.createdAt")
    List<UUID> findIdsByStatusCreatedBefore(@Param("status") StoredFileStatus status, @Param("before") Instant before);
}
