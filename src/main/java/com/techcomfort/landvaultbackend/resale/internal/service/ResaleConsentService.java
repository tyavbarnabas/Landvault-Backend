package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.marketplace.CurrentPlotPrice;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.portfolio.PlotLabel;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.resale.dto.PortalResaleListingDto;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import com.techcomfort.landvaultbackend.resale.internal.exceptions.ResaleException;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sell my plot, phase 4 (SP-14): the developer's Executive Director sees the
 * resale listings on the company's estates and gives or refuses consent where
 * the estate's terms need it. Company-wide only, like revocation. Listings
 * aren't RLS-policied, so the company filter here is the wall. See AGENTS.md,
 * "Sell my plot".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResaleConsentService {

    private final ResaleListingRepository listings;
    private final ResaleListingService listingService;
    private final CheckoutApi checkoutApi;
    private final IdentityApi identityApi;
    private final PortfolioApi portfolioApi;
    private final MarketplaceApi marketplaceApi;
    private final AuditApi auditApi;

    /** @param status a wire status, or null for every one */
    @Transactional(readOnly = true)
    public PageResponse<PortalResaleListingDto> list(String status, Pageable pageable) {
        TenantScope scope = companyWide();
        Set<ListingStatus> statuses = status == null || status.isBlank() ? EnumSet.allOf(ListingStatus.class)
                : EnumSet.of(ListingStatus.fromWire(status));
        Page<ResaleListing> page = listings.findForDeveloper(scope.tenantId(), statuses, pageable);
        Map<UUID, Map<UUID, PlotLabel>> labels = new HashMap<>();
        return PageResponses.from(page, l -> dto(l, labels));
    }

    @Transactional
    public PortalResaleListingDto consent(UUID listingId) {
        TenantScope scope = companyWide();
        ResaleListing listing = awaiting(listingId, scope);
        Instant now = Instant.now();
        listing.setConsentDecidedAt(now);
        listing.setConsentDecidedBy(scope.userId());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "resale.consent_given", "resale_listing",
                listing.getId(), listing.getSellerTenantId(), "Consent given to resell plot " + listing.getPlotId()
                        + "."));
        // Things may have changed while it waited (arrears, a notice, the estate off the marketplace): the same
        // rules as the sweep decide whether it goes live now. The consent is still recorded.
        String reason = listingService.takeDownReason(listing);
        if (reason != null) {
            listingService.close(listing, ListingStatus.ENDED, reason);
            auditApi.record(AuditEntryRequest.of(null, "resale.ended", "resale_listing", listing.getId(), null,
                    "Listing taken down: " + reason + "."));
        } else {
            listingService.goLive(listing, now);
            listings.saveAndFlush(listing);
        }
        log.info("Consent given to resale listing {} ({})", listing.getId(), listing.getStatus());
        return dto(listing, new HashMap<>());
    }

    @Transactional
    public PortalResaleListingDto refuse(UUID listingId, String reason) {
        TenantScope scope = companyWide();
        ResaleListing listing = awaiting(listingId, scope);
        Instant now = Instant.now();
        listing.setConsentDecidedAt(now);
        listing.setConsentDecidedBy(scope.userId());
        listing.setRefusalReason(reason.strip());
        listing.setStatus(ListingStatus.REFUSED);
        listing.setClosedAt(now);
        listings.saveAndFlush(listing);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "resale.consent_refused", "resale_listing",
                listing.getId(), listing.getSellerTenantId(), "Consent refused: " + reason.strip()));
        log.info("Consent refused to resale listing {}", listing.getId());
        return dto(listing, new HashMap<>());
    }

    // --- helpers ---

    /** One of this company's listings, locked, still waiting for an answer that is still due. */
    private ResaleListing awaiting(UUID listingId, TenantScope scope) {
        ResaleListing listing = listings.findLockedByIdAndSellerTenantId(listingId, scope.tenantId())
                .orElseThrow(ResaleException.ListingNotFound::new);
        if (listing.getStatus() != ListingStatus.AWAITING_CONSENT) {
            throw new ResaleException.ConsentNotAwaited(listing.getStatus().wire());
        }
        if (!Instant.now().isBefore(listing.getConsentDueAt())) {
            // The sweep closes it tonight; the seller can list again to ask once more.
            throw new ResaleException.ConsentRequestLapsed();
        }
        return listing;
    }

    /** Consent is a company decision: never from a branch-narrowed view. */
    private static TenantScope companyWide() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        if (scope.tenantId() == null || scope.branchId() != null) {
            throw new ResaleException.ConsentRequiresCompanyWideScope();
        }
        return scope;
    }

    /** @param labels the sellers' plot labels read so far, so each seller's are read once per page */
    private PortalResaleListingDto dto(ResaleListing l, Map<UUID, Map<UUID, PlotLabel>> labels) {
        Optional<PlotLabel> label = Optional.ofNullable(labels.computeIfAbsent(l.getSellerUserId(),
                seller -> new HashMap<>()).computeIfAbsent(l.getPlotId(),
                plot -> portfolioApi.plotLabelOf(l.getSellerUserId(), plot).orElse(null)));
        String reference = checkoutApi.transactionForBuyer(l.getTransactionId(), l.getSellerUserId())
                .map(PayableTransaction::reference).orElse(null);
        BigDecimal price = !l.isEarlySale() ? l.getAskingPrice() : l.isOpen()
                ? marketplaceApi.currentPriceOf(l.getPlotId()).filter(p -> p.currency() == l.getCurrency())
                .map(CurrentPlotPrice::price).orElse(null) : null;
        return new PortalResaleListingDto(l.getId(), l.getStatus().wire(), l.getEstateId(),
                label.map(PlotLabel::estateName).orElse(null), l.getPlotId(), label.map(PlotLabel::plotLabel).orElse(null),
                identityApi.fullNameOf(l.getSellerUserId()).orElse(null), reference, l.getKind().wire(),
                l.isEarlySale(), price, l.getCurrency().name(), l.isRequiresConsent(), l.getCreatedAt(),
                l.getConsentDueAt(), l.getConsentDecidedAt(), l.getRefusalReason(), l.getListedAt(),
                l.getExpiresAt(), l.getClosedAt(), l.getCloseReason());
    }
}
