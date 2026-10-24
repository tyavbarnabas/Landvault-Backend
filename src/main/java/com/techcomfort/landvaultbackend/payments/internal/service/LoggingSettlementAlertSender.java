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
        log.info("[TEST-ONLY late payment outcome] to={} allocated={} letter={}", alert.to(), alert.allocated(),
                alert.letterNumber());
    }

    @Override
    public void instalmentVerified(InstalmentAlert alert) {
        log.info("[TEST-ONLY instalment verified] to={} paid={}/{}", alert.to(), alert.instalmentsPaid(),
                alert.instalmentCount());
    }

    @Override
    public void planWithdrawn(WithdrawalAlert alert) {
        log.info("[TEST-ONLY plan withdrawn] to={} developer={} purchase={} refund={} developerKeeps={}", alert.to(),
                alert.toDeveloper(), alert.purchaseReference(), alert.refund(), alert.developerKeeps());
    }

    @Override
    public void planRestructure(RestructureAlert alert) {
        log.info("[TEST-ONLY plan restructure] to={} developer={} stage={} purchase={} letter={}", alert.to(),
                alert.toDeveloper(), alert.stage(), alert.purchaseReference(), alert.letterNumber());
    }

    @Override
    public void planRevocation(RevocationAlert alert) {
        log.info("[TEST-ONLY plan revocation] to={} stage={} purchase={} deadline={} refund={}", alert.to(),
                alert.stage(), alert.purchaseReference(), alert.deadline(), alert.refund());
    }

    @Override
    public void instalmentReminder(InstalmentReminderAlert alert) {
        log.info("[TEST-ONLY instalment reminder] to={} stage={} instalment={}/{}", alert.to(), alert.stage(),
                alert.sequence(), alert.instalmentCount());
    }

    @Override
    public void purchaseDecided(PurchaseDecisionAlert alert) {
        log.info("[TEST-ONLY purchase decision] to={} verified={} letter={}", alert.to(), alert.verified(),
                alert.letterNumber());
    }
}
