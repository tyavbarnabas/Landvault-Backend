package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.SettlementAccount;
import com.techcomfort.landvaultbackend.payments.internal.enums.SettlementAccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RLS-policied (changeset 074): a company sees only its own rows; platform staff see all. */
public interface SettlementAccountRepository extends JpaRepository<SettlementAccount, UUID> {

    Optional<SettlementAccount> findByTenantIdAndStatus(UUID tenantId, SettlementAccountStatus status);

    List<SettlementAccount> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<SettlementAccount> findAllByStatusOrderByCreatedAtAsc(SettlementAccountStatus status);

    List<SettlementAccount> findAllByOrderByCreatedAtDesc();

    /** Locked, so an approval and a withdrawal of the same submission take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SettlementAccount s WHERE s.id = :id")
    Optional<SettlementAccount> findByIdForUpdate(@Param("id") UUID id);

    /** The company's current approved account, locked while it is superseded. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SettlementAccount s WHERE s.tenantId = :tenantId AND s.status = :status")
    Optional<SettlementAccount> findLockedByTenantIdAndStatus(@Param("tenantId") UUID tenantId,
                                                              @Param("status") SettlementAccountStatus status);
}
