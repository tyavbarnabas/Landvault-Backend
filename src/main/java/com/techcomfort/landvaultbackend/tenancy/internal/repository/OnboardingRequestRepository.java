package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.domain.OnboardingRequest;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.OnboardingRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Platform scope only under RLS, apart from the public insert; see changeset 083. */
public interface OnboardingRequestRepository extends JpaRepository<OnboardingRequest, UUID> {

    Page<OnboardingRequest> findByStatusOrderByCreatedAtDesc(OnboardingRequestStatus status, Pageable pageable);

    Page<OnboardingRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Locked, so two Super Admins acting on one request take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OnboardingRequest r where r.id = :id")
    Optional<OnboardingRequest> findForUpdate(@Param("id") UUID id);
}
