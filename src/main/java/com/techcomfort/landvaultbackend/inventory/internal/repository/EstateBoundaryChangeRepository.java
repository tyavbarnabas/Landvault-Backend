package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateBoundaryChange;
import com.techcomfort.landvaultbackend.inventory.internal.enums.BoundaryChangeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RLS-policied (changeset 069): tenant staff see their own; Super Admins under platform scope see all. */
public interface EstateBoundaryChangeRepository extends JpaRepository<EstateBoundaryChange, UUID> {

    List<EstateBoundaryChange> findByEstateIdOrderByCreatedAtDesc(UUID estateId);

    boolean existsByEstateIdAndStatus(UUID estateId, BoundaryChangeStatus status);

    List<EstateBoundaryChange> findByStatusOrderByCreatedAtAsc(BoundaryChangeStatus status);

    /** Locked, so an approval and a withdrawal of the same request can't both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM EstateBoundaryChange c WHERE c.id = :id")
    Optional<EstateBoundaryChange> findForUpdate(@Param("id") UUID id);
}
