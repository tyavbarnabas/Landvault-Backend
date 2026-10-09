package com.techcomfort.landvaultbackend.payments.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** <strong>Tests and CI only</strong>, so the suite needs no mail server. The alert carries no credential. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.payments.alerts.delivery", havingValue = "log")
public class LoggingSettlementAlertSender implements SettlementAlertSender {

    @Override
    public void accountSubmitted(AccountSubmittedAlert alert) {
        log.info("[TEST-ONLY payout-account alert] to={} company={} last4={}", alert.to(), alert.companyName(),
                alert.accountLast4());
    }

    @Override
    public void payoutProblem(PayoutProblemAlert alert) {
        log.info("[TEST-ONLY payout problem alert] to={} payout={} status={}", alert.to(), alert.payoutReference(),
                alert.status());
    }
}
