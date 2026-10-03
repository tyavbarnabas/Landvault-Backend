package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** Any reservation, in any status — see {@code CheckoutPlotHistoryProbe}. */
    boolean existsByPlotId(UUID plotId);

    List<Reservation> findByBuyerUserIdAndStatusOrderByExpiresAtAsc(UUID buyerUserId, ReservationStatus status);

    Optional<Reservation> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    /**
     * The same lookup, taking the row lock. Every path that decides a hold's
     * fate — opening a transaction, the buyer cancelling, the sweeper — locks
     * the reservation first, so "does a purchase exist?" and "end the hold"
     * cannot interleave. See {@link #findByStatusAndExpiresAtBefore}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findLockedByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    /**
     * The sweeper's query — live holds whose time is up — taken with the row
     * lock, so a transaction being opened against one of them concurrently
     * either commits first (and the sweeper's follow-up existence check sees
     * it) or waits until the hold is closed (and is refused).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant cutoff);
}
