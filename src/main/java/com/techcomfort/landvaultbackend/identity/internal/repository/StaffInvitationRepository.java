package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.StaffInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The table isn't RLS-policied (it is read before any scope exists), so every
 * tenant-facing method takes the caller's tenant id as a mandatory parameter —
 * there is no method that lists invitations without one.
 */
public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, UUID> {

    /** Accept: locked, so two simultaneous accepts of one link can't both create an account. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM StaffInvitation i WHERE i.tokenHash = :hash")
    Optional<StaffInvitation> findByTokenHashForUpdate(@Param("hash") String tokenHash);

    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    Optional<StaffInvitation> findByIdAndTenantId(UUID id, UUID tenantId);

    List<StaffInvitation> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** A branch manager's view: invitations into their own branch only. */
    List<StaffInvitation> findByTenantIdAndScopedBranchIdOrderByCreatedAtDesc(UUID tenantId, UUID scopedBranchId);

    @Query("SELECT COUNT(i) > 0 FROM StaffInvitation i WHERE i.tenantId = :tenantId AND lower(i.email) = lower(:email) "
            + "AND i.acceptedAt IS NULL AND i.revokedAt IS NULL AND i.rejectedAt IS NULL")
    boolean existsOpenFor(@Param("tenantId") UUID tenantId, @Param("email") String email);
}
