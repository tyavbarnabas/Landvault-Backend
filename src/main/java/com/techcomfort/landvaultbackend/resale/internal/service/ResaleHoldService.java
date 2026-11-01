package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.ResaleHoldEnded;
import com.techcomfort.landvaultbackend.checkout.ResaleHoldOpened;
import com.techcomfort.landvaultbackend.checkout.ResaleHoldRequest;
import com.techcomfort.landvaultbackend.common.Naira;
import com.techcomfort.landvaultbackend.kyc.KycApi;
import com.techcomfort.landvaultbackend.resale.dto.ResaleEligibilityDto;
import com.techcomfort.landvaultbackend.resale.dto.ResaleHoldDto;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleHold;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.enums.HoldEndReason;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import com.techcomfort.landvaultbackend.resale.internal.exceptions.ResaleException;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleHoldRepository;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Sell my plot, phase 5a (SP-10, D14, D15, D25): a new buyer holds a resale
 * listing to buy it. The listing is held — never the plot, which stays SOLD or
 * ON_PLAN — through an ordinary checkout reservation, and the price and split
 * are fixed now. When checkout ends the hold, {@link #holdEnded} puts the
 * listing back up in the same transaction. See AGENTS.md, "Sell my plot".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResaleHoldService {

    private final ResaleListingRepository listings;
    private final ResaleHoldRepository holds;
    private final ResaleEligibilityService eligibility;
    private final CheckoutApi checkoutApi;
    private final KycApi kycApi;
    private final AuditApi auditApi;

    @Transactional
    public ResaleHoldDto hold(UUID buyerUserId, UUID listingId) {
        // Locked first, so two buyers (or a buyer and the seller withdrawing) take turns.
        ResaleListing listing = listings.findLockedById(listingId)
                .orElseThrow(ResaleException.ListingNotAvailable::new);
        Instant now = Instant.now();
        if (listing.getStatus() != ListingStatus.LISTED || !now.isBefore(listing.getExpiresAt())) {
            throw new ResaleException.ListingNotAvailable();
        }
        // D15: the seller's own account, or another account verified as the same person.
        if (buyerUserId.equals(listing.getSellerUserId())
                || kycApi.sameVerifiedPerson(buyerUserId, listing.getSellerUserId())) {
            throw new ResaleException.CannotBuyOwnPlot();
        }
        if (!kycApi.isVerified(buyerUserId)) {
            throw new ResaleException.KycRequired();
        }
        // Every listing condition again, now: a seller who fell into arrears overnight, an estate that left
        // the marketplace or an early sale whose price dropped below the agreed one isn't for sale any more.
        ResaleEligibilityDto check = eligibility.eligibility(listing.getSellerUserId(), listing.getTransactionId(),
                listing.getAskingPrice());
        if (!check.canList() || check.quote() == null) {
            throw new ResaleException.ListingNotAvailable();
        }
        ResaleEligibilityDto.Quote quote = check.quote();
        BigDecimal price = quote.price();

        ResaleHoldOpened opened = checkoutApi.holdForResale(new ResaleHoldRequest(buyerUserId, listing.getId(),
                listing.getTransactionId(), listing.getPlotId(), listing.getEstateId(), listing.getSellerTenantId(),
                price, listing.getCurrency(), listing.isRequiresConsent() && listing.getConsentDecidedAt() != null,
                quote.developerReceives(), quote.sellerReceives()));
        // D25: the split is fixed now; nothing the seller or the developer does afterwards moves it.
        holds.saveAndFlush(ResaleHold.builder()
                .listingId(listing.getId())
                .sellerTransactionId(listing.getTransactionId())
                .reservationId(opened.reservationId())
                .buyerUserId(buyerUserId)
                .heldAt(now)
                .price(price)
                .currency(listing.getCurrency())
                .earlySale(listing.isEarlySale())
                .developerBalance(quote.developerBalance())
                .penaltiesOwed(quote.penaltiesOwed())
                .gain(quote.gain())
                .developerGainSharePct(quote.developerGainSharePct())
                .developerGainShare(quote.developerGainShare())
                .transferFee(quote.transferFee())
                .developerReceives(quote.developerReceives())
                .sellerReceives(quote.sellerReceives())
                .build());
        listing.setStatus(ListingStatus.HELD);
        listings.saveAndFlush(listing);
        auditApi.record(AuditEntryRequest.of(buyerUserId, "resale.held", "resale_listing", listing.getId(),
                listing.getSellerTenantId(), "Held by a buyer at " + Naira.format(price) + "; the seller's plan is "
                        + "paused until the hold ends."));
        log.info("Resale listing {} held by buyer {} (reservation {})", listing.getId(), buyerUserId,
                opened.reservationId());
        return new ResaleHoldDto(opened.reservationId(), listing.getId(), listing.getEstateId(), listing.getPlotId(),
                price, listing.getCurrency().name(), opened.expiresAt(),
                Math.max(0, Duration.between(Instant.now(), opened.expiresAt()).toSeconds()));
    }

    /**
     * Checkout ended a resale hold — released, expired, abandoned, rejected —
     * inside its own transaction: the listing goes back up, and its 90 days
     * move on by the time it was held. A converted hold is the sale (phase 6)
     * and doesn't put it back. Synchronous, like every listener that must
     * commit or roll back with its cause.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdEnded(ResaleHoldEnded event) {
        ResaleHold hold = holds.findByReservationId(event.reservationId()).orElse(null);
        if (hold == null || !hold.isOpen()) {
            return;
        }
        HoldEndReason reason = HoldEndReason.fromReservationOutcome(event.outcome());
        hold.setEndedAt(event.endedAt());
        hold.setEndReason(reason);
        holds.saveAndFlush(hold);
        ResaleListing listing = listings.findLockedById(hold.getListingId()).orElseThrow();
        if (reason == HoldEndReason.CONVERTED || listing.getStatus() != ListingStatus.HELD) {
            return;
        }
        listing.setStatus(ListingStatus.LISTED);
        listing.setExpiresAt(listing.getExpiresAt().plus(Duration.between(hold.getHeldAt(), event.endedAt())));
        listings.saveAndFlush(listing);
        auditApi.record(AuditEntryRequest.of(null, "resale.hold_ended", "resale_listing", listing.getId(),
                listing.getSellerTenantId(), "The buyer's hold ended (" + event.outcome() + "); the plot is back "
                        + "on the marketplace."));
        log.info("Resale listing {} back up after its hold ended ({})", listing.getId(), event.outcome());
    }
}
