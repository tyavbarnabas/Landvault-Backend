package com.techcomfort.landvaultbackend.resale.internal.service;

import java.time.LocalDate;

/**
 * Sell my plot, phase 4: the emails to the developer when a plot on one of
 * their estates is listed for resale. {@code landvault.resale.delivery}
 * selects the implementation, like the other mailers; {@code log} is for tests
 * only. Neither carries a credential.
 */
public interface ResaleMailer {

    /** The estate needs consent: the Executive Director is asked to give or refuse it by {@code answerBy}. */
    void consentRequested(ListingAlert alert, LocalDate answerBy);

    /** No consent needed: the Executive Director is told the plot is now on the marketplace. */
    void listedNotice(ListingAlert alert);

    /**
     * @param price         as the buyer will see it, e.g. "₦18,000,000.00"; an early sale's is the developer's
     *                      current price
     * @param earlySale     the seller hasn't yet reached the profit thresholds, so the developer is paid the gain
     */
    record ListingAlert(String to, String sellerName, String transactionReference, String estateName,
                        String plotLabel, String price, boolean earlySale, String reviewUrl) {
    }
}
