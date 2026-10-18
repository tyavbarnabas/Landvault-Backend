package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.Letterhead;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads run under the caller's RLS: a branch-scoped caller sees the company's letterhead and their own branch's. */
public interface LetterheadRepository extends JpaRepository<Letterhead, UUID> {

    List<Letterhead> findByTenantId(UUID tenantId);

    /** The company's letterhead, locked so a change and its version bump are one step. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Letterhead l WHERE l.tenantId = :tenantId AND l.branchId IS NULL")
    Optional<Letterhead> findCompanyForUpdate(@Param("tenantId") UUID tenantId);

    /** One branch's own letterhead, locked likewise. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Letterhead l WHERE l.tenantId = :tenantId AND l.branchId = :branchId")
    Optional<Letterhead> findBranchForUpdate(@Param("tenantId") UUID tenantId, @Param("branchId") UUID branchId);
}
