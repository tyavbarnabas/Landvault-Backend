package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.common.Naira;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.portfolio.PlotLabel;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Sell my plot, phase 4: tells the developer's Executive Directors that a plot
 * on their estate was listed — asking for consent where the estate needs it,
 * otherwise a notice. Sent after commit, and a failed email never undoes the
 * listing (the {@code BuyerNotifier} rule).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResaleNotices {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final IdentityApi identityApi;
    private final PortfolioApi portfolioApi;
    private final ResaleMailer mailer;
    private final ResaleProperties properties;

    /** @param price what buyers will see; on an early sale, the developer's current price */
    void listed(ResaleListing listing, String transactionReference, BigDecimal price) {
        List<String> directors = identityApi.executiveDirectorEmails(listing.getSellerTenantId());
        if (directors.isEmpty()) {
            log.warn("No active Executive Director to tell about resale listing {}", listing.getId());
            return;
        }
        String seller = identityApi.fullNameOf(listing.getSellerUserId()).orElse("A buyer");
        PlotLabel label = portfolioApi.plotLabelOf(listing.getSellerUserId(), listing.getPlotId())
                .orElse(new PlotLabel("your estate", "a plot"));
        String shown = price == null ? "your current price" : Naira.format(price);
        LocalDate answerBy = listing.isRequiresConsent()
                ? LocalDate.ofInstant(listing.getConsentDueAt(), LAGOS) : null;
        for (String to : directors) {
            ResaleMailer.ListingAlert alert = new ResaleMailer.ListingAlert(to, seller, transactionReference,
                    label.estateName(), label.plotLabel(), shown, listing.isEarlySale(), properties.getReviewUrl());
            afterCommit(answerBy != null ? () -> mailer.consentRequested(alert, answerBy)
                    : () -> mailer.listedNotice(alert));
        }
    }

    private static void afterCommit(Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendQuietly(send);
                }
            });
        } else {
            sendQuietly(send);
        }
    }

    private static void sendQuietly(Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            // The exception's class only: a mail error's message can carry the recipient's address.
            log.warn("Resale email could not be sent ({}); the listing stands", e.getClass().getSimpleName());
        }
    }
}
