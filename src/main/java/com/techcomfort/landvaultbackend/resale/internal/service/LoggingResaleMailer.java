package com.techcomfort.landvaultbackend.resale.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** <strong>Tests and CI only</strong>, so the suite needs no mail server. No email here carries a credential. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.resale.delivery", havingValue = "log")
public class LoggingResaleMailer implements ResaleMailer {

    @Override
    public void consentRequested(ListingAlert alert, LocalDate answerBy) {
        log.info("[TEST-ONLY resale consent request] reference={} answerBy={}", alert.transactionReference(),
                answerBy);
    }

    @Override
    public void listedNotice(ListingAlert alert) {
        log.info("[TEST-ONLY resale listed notice] reference={}", alert.transactionReference());
    }
}
