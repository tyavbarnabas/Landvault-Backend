package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByBuyerUserIdAndStatusOrderByExpiresAtAsc(UUID buyerUserId, ReservationStatus status);

    Optional<Reservation> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    /** The sweeper's query — live holds whose time is up, oldest first. */
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant cutoff);
}
