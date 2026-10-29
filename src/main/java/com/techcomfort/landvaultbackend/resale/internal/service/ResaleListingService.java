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
 * made — never a stale answer. See AGENTS.md, "Sell my plot".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResaleListingService {

    /** D27: a listing lasts this long with no buyer. */
    public static final Duration LISTING_LIFETIME = Duration.ofDays(90);

    /**
     * What takes a live listing down (decided with the user 2026-10-29). Not a
     * payment waiting for finance: a seller may keep paying while listed.
     */
    private static final Set<String> TAKE_DOWN = Set.of("plan_ended", "not_owned_yet", "in_arrears",
            "notice_running", "estate_not_listed", "early_sale_below_agreed_price");

    private static final Map<String, String> CLOSE_MESSAGES = Map.of(
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
        if (listings.existsByTransactionIdAndStatus(check.transactionId(), ListingStatus.LISTED)) {
            throw new ResaleException.AlreadyListed();
        }
        PayableTransaction purchase = checkoutApi.transactionForBuyer(check.transactionId(), sellerUserId)
                .orElseThrow(ResaleException.PlotNotFound::new);
        ListingKind kind = ListingKind.fromWire(check.kind());
        Instant now = Instant.now();
        ResaleListing listing = listings.saveAndFlush(ResaleListing.builder()
                .transactionId(purchase.id())
                .sellerUserId(sellerUserId)
                .sellerTenantId(purchase.sellerTenantId())
                .estateId(purchase.estateId())
                .plotId(purchase.plotId())
                .kind(kind)
                .earlySale(early)
                .askingPrice(early ? null : price)
                .currency(purchase.currency())
                .status(ListingStatus.LISTED)
                .listedAt(now)
                .expiresAt(now.plus(LISTING_LIFETIME))
                // D24: only the first listing on a flexible plan stops its inactivity clock.
                .pausesInactivity(kind == ListingKind.FLEXIBLE
                        && !listings.existsByTransactionIdAndPausesInactivityTrue(purchase.id()))
                .build());
        auditApi.record(AuditEntryRequest.of(sellerUserId, "resale.listed", "resale_listing", listing.getId(), null,
                "Purchase " + purchase.reference() + " listed for resale "
                        + (early ? "as an early sale at the developer's price" : "at " + Naira.format(price)) + "."));
        log.info("Resale listing {} created for purchase {}", listing.getId(), purchase.id());
        return dto(listing, purchase.reference(), check);
    }

    @Transactional
    public ResaleListingDto changePrice(UUID sellerUserId, UUID listingId, BigDecimal price) {
        ResaleListing listing = listings.findLockedByIdAndSellerUserId(listingId, sellerUserId)
                .orElseThrow(ResaleException.ListingNotFound::new);
        requireLive(listing);
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
        requireLive(listing);
        close(listing, ListingStatus.WITHDRAWN, null);
        auditApi.record(AuditEntryRequest.of(sellerUserId, "resale.withdrawn", "resale_listing", listing.getId(),
                null, "Listing taken down by the seller."));
        return dto(listing, reference(listing), null);
    }

    @Transactional(readOnly = true)
    public List<ResaleListingDto> mine(UUID sellerUserId) {
        return listings.findBySellerUserIdOrderByListedAtDesc(sellerUserId).stream()
                .map(l -> dto(l, reference(l), l.isLive()
                        ? eligibility.eligibility(sellerUserId, l.getTransactionId(), l.getAskingPrice()) : null))
                .toList();
    }

    // --- the sweep (SP-4, D27) ---

    @Transactional(readOnly = true)
    public List<UUID> liveListingIds() {
        return listings.findByStatus(ListingStatus.LISTED).stream().map(ResaleListing::getId).toList();
    }

    /** Takes one live listing down if its time is up or a rule says so; returns the status it ends with. */
    @Transactional
    public ListingStatus sweep(UUID listingId, Instant now) {
        ResaleListing listing = listings.findLockedById(listingId).orElseThrow();
        if (!listing.isLive()) {
            return listing.getStatus();
        }
        if (!now.isBefore(listing.getExpiresAt())) {
            close(listing, ListingStatus.EXPIRED, null);
            auditApi.record(AuditEntryRequest.of(null, "resale.expired", "resale_listing", listing.getId(), null,
                    "Listing came down after 90 days with no buyer."));
            return ListingStatus.EXPIRED;
        }
        ResaleEligibilityDto check = eligibility.eligibility(listing.getSellerUserId(), listing.getTransactionId(),
                null);
        String reason = check.reasons().stream().map(ResaleEligibilityDto.Reason::code).filter(TAKE_DOWN::contains)
                .findFirst().map(code -> "not_owned_yet".equals(code) ? "plan_ended" : code).orElse(null);
        if (reason == null) {
            return ListingStatus.LISTED;
        }
        close(listing, ListingStatus.ENDED, reason);
        auditApi.record(AuditEntryRequest.of(null, "resale.ended", "resale_listing", listing.getId(), null,
                "Listing taken down: " + reason + "."));
        return ListingStatus.ENDED;
    }

    // --- reading ---

    private static void requireLive(ResaleListing listing) {
        if (!listing.isLive()) {
            throw new ResaleException.ListingNotLive(listing.getStatus().wire());
        }
    }

    private void close(ResaleListing listing, ListingStatus status, String reason) {
        listing.setStatus(status);
        listing.setClosedAt(Instant.now());
        listing.setCloseReason(reason);
        listings.saveAndFlush(listing);
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
                l.isPausesInactivity(), l.isLive() && check != null ? check.quote() : null);
    }
}
