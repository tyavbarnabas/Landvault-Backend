package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.common.Naira;
import com.techcomfort.landvaultbackend.resale.dto.ResaleEligibilityDto;
import com.techcomfort.landvaultbackend.resale.dto.ResaleListingDto;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingKind;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import com.techcomfort.landvaultbackend.resale.internal.exceptions.ResaleException;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleHoldRepository;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sell my plot, phase 2 (SP-2..SP-4): list a plot, change its price, take it
 * down; a daily sweep takes a listing down after 90 days or when a rule says
 * so. Listing re-checks every condition of the eligibility read when it is
 * made — never a stale answer. Phase 4: on an estate needing the developer's
 * consent a listing waits off the marketplace until {@link ResaleConsentService}
 * records an answer, and lapses after 14 days without one. See AGENTS.md,
 * "Sell my plot".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResaleListingService {

    /** D27: a listing lasts this long with no buyer. */
    public static final Duration LISTING_LIFETIME = Duration.ofDays(90);

    /** Phase 4: how long the developer has to give or refuse consent. */
    public static final Duration CONSENT_WINDOW = Duration.ofDays(14);

    /**
     * What takes a live listing down (decided with the user 2026-10-29). Not a
     * payment waiting for finance: a seller may keep paying while listed.
     */
    private static final Set<String> TAKE_DOWN = Set.of("plan_ended", "not_owned_yet", "in_arrears",
            "notice_running", "estate_not_listed", "early_sale_below_agreed_price");

    static final Map<String, String> CLOSE_MESSAGES = Map.of(
            "consent_not_given", "The developer didn't answer within 14 days. You can list the plot again to ask "
                    + "once more.",
            "plan_ended", "Your purchase ended, so there is no plot to sell.",
            "in_arrears", "Your plan fell into arrears. Pay what is overdue, then list it again.",
            "notice_running", "The developer gave notice on your plan. List it again once the notice has ended.",
            "estate_not_listed", "The estate left the marketplace. List it again once it is back.",
            "early_sale_below_agreed_price", "The developer's price fell below your agreed price, so an early "
                    + "sale is no longer offered.");

    private final ResaleListingRepository listings;
    private final ResaleEligibilityService eligibility;
    private final CheckoutApi checkoutApi;
    private final AuditApi auditApi;
    private final ResaleNotices notices;
    private final ResaleHoldRepository holds;

    @Transactional
    public ResaleListingDto list(UUID sellerUserId, UUID plotOrTransactionId, BigDecimal price) {
        ResaleEligibilityDto check = eligibility.eligibility(sellerUserId, plotOrTransactionId, price);
        if (!check.canList()) {
            throw new ResaleException.NotAllowed(check.reasons().stream().map(ResaleEligibilityDto.Reason::message)
                    .collect(Collectors.joining(" ")));
        }
        boolean early = Boolean.TRUE.equals(check.earlySale());
        if (!early && price == null) {
            throw new ResaleException.InvalidPrice("Name your asking price.");
        }
        if (listings.existsByTransactionIdAndStatusIn(check.transactionId(), ListingStatus.OPEN)) {
            throw new ResaleException.AlreadyListed();
        }
        PayableTransaction purchase = checkoutApi.transactionForBuyer(check.transactionId(), sellerUserId)
                .orElseThrow(ResaleException.PlotNotFound::new);
        ListingKind kind = ListingKind.fromWire(check.kind());
        // The estate's terms now decide, and the listing keeps the answer (a later change doesn't reach it).
        boolean needsConsent = Boolean.TRUE.equals(check.consentRequired());
        Instant now = Instant.now();
        ResaleListing listing = ResaleListing.builder()
                .transactionId(purchase.id())
                .sellerUserId(sellerUserId)
                .sellerTenantId(purchase.sellerTenantId())
                .estateId(purchase.estateId())
                .plotId(purchase.plotId())
                .kind(kind)
                .earlySale(early)
                .askingPrice(early ? null : price)
                .currency(purchase.currency())
                .status(needsConsent ? ListingStatus.AWAITING_CONSENT : ListingStatus.LISTED)
                .requiresConsent(needsConsent)
                .consentDueAt(needsConsent ? now.plus(CONSENT_WINDOW) : null)
                .build();
        if (!needsConsent) {
            goLive(listing, now);
        }
        listing = listings.saveAndFlush(listing);
        String at = early ? "as an early sale at the developer's price" : "at " + Naira.format(price);
        auditApi.record(AuditEntryRequest.of(sellerUserId, needsConsent ? "resale.consent_requested" : "resale.listed",
                "resale_listing", listing.getId(), null, "Purchase " + purchase.reference() + " listed for resale "
                        + at + (needsConsent ? "; waiting for the developer's consent." : ".")));
        log.info("Resale listing {} created for purchase {}{}", listing.getId(), purchase.id(),
                needsConsent ? ", awaiting consent" : "");
        notices.listed(listing, purchase.reference(), early ? check.developerPrice() : price);
        return dto(listing, purchase.reference(), check);
    }

    @Transactional
    public ResaleListingDto changePrice(UUID sellerUserId, UUID listingId, BigDecimal price) {
        ResaleListing listing = listings.findLockedByIdAndSellerUserId(listingId, sellerUserId)
                .orElseThrow(ResaleException.ListingNotFound::new);
        requireOpen(listing);
        if (listing.isEarlySale()) {
            throw new ResaleException.InvalidPrice("An early sale's price is the developer's current price; it can't "
                    + "be changed.");
        }
        // The same price rules as listing (whole kobo, not below the agreed price on a plan).
        ResaleEligibilityDto check = eligibility.eligibility(sellerUserId, listing.getTransactionId(), price);
        if (price.compareTo(listing.getAskingPrice()) != 0) {
            BigDecimal before = listing.getAskingPrice();
            listing.setAskingPrice(price);
            listings.saveAndFlush(listing);
            auditApi.record(AuditEntryRequest.of(sellerUserId, "resale.price_changed", "resale_listing",
                    listing.getId(), null, "Asking price changed from " + Naira.format(before) + " to "
                            + Naira.format(price) + "."));
        }
        return dto(listing, check.transactionReference(), check);
    }

    @Transactional
    public ResaleListingDto withdraw(UUID sellerUserId, UUID listingId) {
        ResaleListing listing = listings.findLockedByIdAndSellerUserId(listingId, sellerUserId)
                .orElseThrow(ResaleException.ListingNotFound::new);
        requireOpen(listing);
        close(listing, ListingStatus.WITHDRAWN, null);
        auditApi.record(AuditEntryRequest.of(sellerUserId, "resale.withdrawn", "resale_listing", listing.getId(),
                null, "Listing taken down by the seller."));
        return dto(listing, reference(listing), null);
    }

    @Transactional(readOnly = true)
    public List<ResaleListingDto> mine(UUID sellerUserId) {
        return listings.findBySellerUserIdOrderByCreatedAtDesc(sellerUserId).stream()
                .map(l -> heldQuote(dto(l, reference(l), l.isOpen()
                        ? eligibility.eligibility(sellerUserId, l.getTransactionId(), l.getAskingPrice()) : null), l))
                .toList();
    }

    // --- the sweep (SP-4, D27) ---

    /** Every listing not over yet: on the marketplace, or waiting for consent. */
    @Transactional(readOnly = true)
    public List<UUID> liveListingIds() {
        return listings.findByStatusIn(ListingStatus.OPEN).stream().map(ResaleListing::getId).toList();
    }

    /**
     * Takes one open listing down if its time is up or a rule says so; returns
     * the status it ends with. A consent request with no answer by its due time
     * lapses as {@code consent_not_given}.
     */
    @Transactional
    public ListingStatus sweep(UUID listingId, Instant now) {
        ResaleListing listing = listings.findLockedById(listingId).orElseThrow();
        // Held by a buyer (D25): nothing comes down, and its 90 days wait until the hold ends.
        if (!listing.isOpen() || listing.getStatus() == ListingStatus.HELD) {
            return listing.getStatus();
        }
        if (listing.getStatus() == ListingStatus.AWAITING_CONSENT && !now.isBefore(listing.getConsentDueAt())) {
            close(listing, ListingStatus.ENDED, "consent_not_given");
            auditApi.record(AuditEntryRequest.of(null, "resale.consent_lapsed", "resale_listing", listing.getId(),
                    null, "The developer didn't answer the consent request within 14 days."));
            return ListingStatus.ENDED;
        }
        if (listing.isLive() && !now.isBefore(listing.getExpiresAt())) {
            close(listing, ListingStatus.EXPIRED, null);
            auditApi.record(AuditEntryRequest.of(null, "resale.expired", "resale_listing", listing.getId(), null,
                    "Listing came down after 90 days with no buyer."));
            return ListingStatus.EXPIRED;
        }
        String reason = takeDownReason(listing);
        if (reason == null) {
            return listing.getStatus();
        }
        close(listing, ListingStatus.ENDED, reason);
        auditApi.record(AuditEntryRequest.of(null, "resale.ended", "resale_listing", listing.getId(), null,
                "Listing taken down: " + reason + "."));
        return ListingStatus.ENDED;
    }

    // --- shared with ResaleConsentService ---

    /** The first rule that says this listing must come down now, or null. */
    String takeDownReason(ResaleListing listing) {
        ResaleEligibilityDto check = eligibility.eligibility(listing.getSellerUserId(), listing.getTransactionId(),
                null);
        return check.reasons().stream().map(ResaleEligibilityDto.Reason::code).filter(TAKE_DOWN::contains)
                .findFirst().map(code -> "not_owned_yet".equals(code) ? "plan_ended" : code).orElse(null);
    }

    /**
     * On the marketplace from {@code now}, for 90 days. On a flexible plan the
     * first listing to go live stops the inactivity clock (D24) — decided here,
     * so a listing refused or never answered doesn't use up the pause.
     */
    void goLive(ResaleListing listing, Instant now) {
        listing.setStatus(ListingStatus.LISTED);
        listing.setListedAt(now);
        listing.setExpiresAt(now.plus(LISTING_LIFETIME));
        listing.setPausesInactivity(listing.getKind() == ListingKind.FLEXIBLE
                && !listings.existsByTransactionIdAndPausesInactivityTrue(listing.getTransactionId()));
    }

    void close(ResaleListing listing, ListingStatus status, String reason) {
        listing.setStatus(status);
        listing.setClosedAt(Instant.now());
        listing.setCloseReason(reason);
        listings.saveAndFlush(listing);
    }

    // --- reading ---

    /** Open, and not held by a buyer (D25): what the seller may still change or take down. */
    private static void requireOpen(ResaleListing listing) {
        if (!listing.isOpen()) {
            throw new ResaleException.ListingNotLive(listing.getStatus().wire());
        }
        if (listing.getStatus() == ListingStatus.HELD) {
            throw new ResaleException.ListingHeld();
        }
    }

    /** D25: while held, the price and split are the ones fixed when the buyer took the hold. */
    private ResaleListingDto heldQuote(ResaleListingDto dto, ResaleListing listing) {
        if (listing.getStatus() != ListingStatus.HELD) {
            return dto;
        }
        return holds.findByListingIdAndEndedAtIsNull(listing.getId()).map(h -> new ResaleListingDto(dto.id(),
                dto.transactionId(), dto.transactionReference(), dto.kind(), dto.status(), dto.earlySale(), h.getPrice(),
                dto.currency(), dto.listedAt(), dto.expiresAt(), dto.closedAt(), dto.closeReason(), dto.closeMessage(),
                dto.pausesInactivity(), new ResaleEligibilityDto.Quote(h.getPrice(), h.getDeveloperBalance(),
                h.getPenaltiesOwed(), h.getGain(), h.getDeveloperGainSharePct(), h.getDeveloperGainShare(),
                h.getTransferFee(), h.getDeveloperReceives(), h.getSellerReceives(),
                dto.quote() != null && dto.quote().reviewRequired()), dto.requiresConsent(), dto.consentDueAt(),
                dto.consentDecidedAt(), dto.refusalReason())).orElse(dto);
    }

    private String reference(ResaleListing listing) {
        return checkoutApi.transactionForBuyer(listing.getTransactionId(), listing.getSellerUserId())
                .map(PayableTransaction::reference).orElse(null);
    }

    private static ResaleListingDto dto(ResaleListing l, String reference, ResaleEligibilityDto check) {
        BigDecimal price = l.isEarlySale() ? (check == null ? null : check.developerPrice()) : l.getAskingPrice();
        return new ResaleListingDto(l.getId(), l.getTransactionId(), reference, l.getKind().wire(),
                l.getStatus().wire(), l.isEarlySale(), price, l.getCurrency().name(), l.getListedAt(),
                l.getExpiresAt(), l.getClosedAt(), l.getCloseReason(),
                l.getCloseReason() == null ? null : CLOSE_MESSAGES.get(l.getCloseReason()),
                l.isPausesInactivity(), l.isOpen() && check != null ? check.quote() : null, l.isRequiresConsent(),
                l.getConsentDueAt(), l.getConsentDecidedAt(), l.getRefusalReason());
    }
}
