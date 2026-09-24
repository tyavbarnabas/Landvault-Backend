package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.dto.ReservationDto;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.checkout.internal.exceptions.CheckoutException;
import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PlotPricing;
import com.techcomfort.landvaultbackend.kyc.KycApi;
import com.techcomfort.landvaultbackend.marketplace.EstateEligibility;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Holding a plot, and giving it back.
 * <p>
 * The single correctness property this whole module exists for is that two
 * buyers can never both hold one plot — double allocation at the point of
 * sale is the exact fraud this platform is pitched on preventing. That
 * guarantee lives in {@link PlotLockGateway}'s compare-and-swap, not in any
 * check-then-write here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(CheckoutProperties.class)
public class ReservationService {

    private static final String ACTOR = "reservation";

    private final ReservationRepository reservationRepository;
    private final PlotLockGateway plotLockGateway;
    private final MarketplaceApi marketplaceApi;
    private final KycApi kycApi;
    private final AuditApi auditApi;
    private final CheckoutProperties properties;

    /**
     * Verification, then eligibility, then the atomic acquire — in that
     * order, and the order is deliberate: the two cheap refusals that can be
     * explained precisely happen before the one that cannot say anything
     * beyond "no".
     */
    @Transactional
    public ReservationDto reserve(UUID buyerUserId, UUID plotId) {
        // KY-1: verification gates buying, never browsing or signup. This is
        // where a buyer meets it for the first time.
        if (!kycApi.isVerified(buyerUserId)) {
            throw new CheckoutException.KycRequired();
        }

        // The plot's estate, via the public projection — a buyer has no
        // tenant scope, so plots is unreadable to them through JPA. Absent
        // means "no such plot, or not currently listed", reported as one
        // thing on purpose.
        UUID estateId = marketplaceApi.estateIdOfPlot(plotId)
                .orElseThrow(CheckoutException.PlotNotAvailable::new);

        // TX-4: the five publication conditions re-evaluated now, never
        // trusted from whatever the browse response said earlier. A tenant
        // suspended, or a HIGH conflict raised, since the page loaded stops
        // the sale here.
        requireEligible(estateId);

        PlotLockGateway.AcquiredPlot acquired = plotLockGateway.acquire(plotId, ACTOR)
                .orElseThrow(CheckoutException.PlotNotAvailable::new);

        // Computed here, from the locked snapshot, by the one helper every
        // other surface prices a plot with. Never read from the request:
        // a client-supplied price is the same class of hole as a
        // client-supplied tenantId.
        BigDecimal cornerPremiumPct = Boolean.TRUE.equals(acquired.isCorner())
                ? acquired.cornerPremiumPct()
                : null;
        BigDecimal totalPrice = PlotPricing.price(
                acquired.tierPrice(), Boolean.TRUE.equals(acquired.isCorner()), acquired.cornerPremiumPct());

        Instant now = Instant.now();
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .buyerUserId(buyerUserId)
                .plotId(plotId)
                .estateId(acquired.estateId())
                .sellerTenantId(acquired.sellerTenantId())
                .priceTierId(acquired.priceTierId())
                .previousPlotStatus(acquired.previousStatus())
                .basePrice(acquired.tierPrice())
                .cornerPremiumPct(cornerPremiumPct)
                .totalPrice(totalPrice)
                .currency(Currency.valueOf(acquired.tierCurrency()))
                .status(ReservationStatus.ACTIVE)
                .expiresAt(now.plus(holdDuration()))
                .build());

        log.info("Plot {} reserved by buyer {} until {}", plotId, buyerUserId, reservation.getExpiresAt());
        auditApi.record(AuditEntryRequest.of(
                buyerUserId, "reservation.created", "reservation", reservation.getId(),
                acquired.sellerTenantId(), "Plot held for " + holdDuration().toMinutes() + " minutes"));

        return toDto(reservation, now);
    }

    /** A buyer's own live holds. Another buyer's are not reachable from here. */
    @Transactional(readOnly = true)
    public List<ReservationDto> activeFor(UUID buyerUserId) {
        Instant now = Instant.now();
        return reservationRepository
                .findByBuyerUserIdAndStatusOrderByExpiresAtAsc(buyerUserId, ReservationStatus.ACTIVE)
                .stream()
                .map(reservation -> toDto(reservation, now))
                .toList();
    }

    /**
     * RS-4. Scoped to the caller's own reservation by the query itself, so
     * another buyer's hold is not found rather than forbidden — a 403 would
     * confirm the id exists.
     */
    @Transactional
    public void release(UUID buyerUserId, UUID reservationId) {
        Reservation reservation = reservationRepository
                .findByIdAndBuyerUserId(reservationId, buyerUserId)
                .orElseThrow(CheckoutException.ReservationNotFound::new);

        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new CheckoutException.ReservationNotActive(
                    "That reservation is already " + reservation.getStatus().getValue() + ".");
        }

        endHold(reservation, ReservationStatus.RELEASED, buyerUserId, "reservation.released",
                "Hold released by the buyer");
    }

    /**
     * RS-3: every hold whose time is up, returned to the pool.
     * <p>
     * Separate from the scheduled trigger so the sweep is callable directly
     * — a test must be able to run it without waiting on a clock, and a
     * future operator endpoint would use the same method.
     * <p>
     * One transaction for the batch: a failure rolls the whole sweep back
     * and the next one retries it, which is safer than half-releasing a set
     * of plots and having no record of where it stopped.
     */
    @Transactional
    public int releaseExpired() {
        List<Reservation> expired = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, Instant.now());

        for (Reservation reservation : expired) {
            // The actor is the SYSTEM, not the buyer (TX-5): nobody chose
            // this, and attributing it to the buyer would misrepresent the
            // record a disputed allocation is later reconstructed from.
            endHold(reservation, ReservationStatus.EXPIRED, null, "reservation.expired",
                    "Hold expired and the plot returned to the pool");
        }
        if (!expired.isEmpty()) {
            log.info("Released {} expired hold(s)", expired.size());
        }
        return expired.size();
    }

    /**
     * Ends a hold and returns the plot to the pool, restoring the exact
     * availability variant it had before. Shared by cancellation and expiry
     * so the two cannot drift apart.
     * <p>
     * The release is idempotent: if the plot is no longer {@code RESERVED}
     * (the sweeper and a cancel racing, say), the gateway reports false and
     * the reservation is still closed. Leaving it {@code ACTIVE} because the
     * plot had already moved on would strand a hold nobody can clear.
     */
    void endHold(Reservation reservation, ReservationStatus endStatus, UUID actorUserId,
                 String auditAction, String auditDetail) {

        boolean releasedNow = plotLockGateway.release(
                reservation.getPlotId(), reservation.getPreviousPlotStatus(), ACTOR);
        if (!releasedNow) {
            log.warn("Plot {} was not held when reservation {} ended as {} — closing the reservation anyway",
                    reservation.getPlotId(), reservation.getId(), endStatus.getValue());
        }

        reservation.setStatus(endStatus);
        reservation.setEndedAt(Instant.now());
        reservationRepository.save(reservation);

        auditApi.record(new AuditEntryRequest(
                actorUserId, auditAction, "reservation", reservation.getId(),
                reservation.getSellerTenantId(), auditDetail, false));
    }

    private void requireEligible(UUID estateId) {
        EstateEligibility eligibility = marketplaceApi.eligibilityOf(estateId)
                .orElseThrow(CheckoutException.PlotNotAvailable::new);
        if (eligibility.eligible()) {
            return;
        }
        // Named in a fixed order, most specific cause first — and never
        // naming the counterparty of a conflict, which the buyer has no
        // business knowing.
        String reason;
        if (!eligibility.published()) {
            reason = "This listing is no longer published.";
        } else if (!eligibility.tenantVerified()) {
            reason = "The selling company's verification is not currently in good standing.";
        } else if (!eligibility.tenantEntitled() || !eligibility.tenantActive()) {
            reason = "This listing is not currently available for purchase.";
        } else {
            reason = "A boundary review is open on this estate, so it cannot be sold right now.";
        }
        throw new CheckoutException.EstateNotAvailable(reason);
    }

    private Duration holdDuration() {
        return properties.holdDuration();
    }

    private ReservationDto toDto(Reservation reservation, Instant now) {
        long secondsRemaining = Math.max(0, Duration.between(now, reservation.getExpiresAt()).toSeconds());
        return new ReservationDto(
                reservation.getId(),
                reservation.getEstateId(),
                reservation.getPlotId(),
                reservation.getPriceTierId(),
                reservation.getStatus().getValue(),
                reservation.getExpiresAt(),
                secondsRemaining);
    }
}
