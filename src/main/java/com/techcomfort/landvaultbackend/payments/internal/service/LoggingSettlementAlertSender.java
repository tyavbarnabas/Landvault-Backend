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

    @Override
    public void refundNeedsAccount(RefundNeedsAccountAlert alert) {
        log.info("[TEST-ONLY refund needs account] to={} payment={}", alert.to(), alert.paymentReference());
    }

    @Override
    public void refundProblem(RefundProblemAlert alert) {
        log.info("[TEST-ONLY refund problem alert] to={} payment={} status={}", alert.to(), alert.paymentReference(),
                alert.status());
    }

    @Override
    public void latePaymentResolved(LatePaymentAlert alert) {
        log.info("[TEST-ONLY late payment outcome] to={} allocated={}", alert.to(), alert.allocated());
    }
}
