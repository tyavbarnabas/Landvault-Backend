package com.techcomfort.landvaultbackend.payments.internal.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends the payout-account alert by SMTP — same arrangement as the invitation and OTP emails. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.payments.alerts.delivery", havingValue = "email", matchIfMissing = true)
public class EmailSettlementAlertSender implements SettlementAlertSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;

    private JavaMailSender mailSender;

    public EmailSettlementAlertSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                      @Value("${landvault.payments.alerts.from-address}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
    }

    @PostConstruct
    void init() {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("No JavaMailSender is available, so payout-account alerts could not "
                    + "be sent. Set spring.mail.* for this environment (locally: the dev profile — see AGENTS.md).");
        }
    }

    @Override
    public void accountSubmitted(AccountSubmittedAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("A new payout bank account was submitted for " + alert.companyName());
        message.setText("""
                Hello,

                A new bank account for %s's LandVault payouts was submitted by %s:

                    %s, account ending %s

                It won't receive any money until LandVault approves it. If you didn't expect this change, \
                contact LandVault support straight away — before it is approved.
                """.formatted(alert.companyName(), alert.submittedByEmail(), alert.bankName(), alert.accountLast4()));
        mailSender.send(message);
        log.info("Payout-account alert delivered to an Executive Director of {}", alert.companyName());
    }

    @Override
    public void payoutProblem(PayoutProblemAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Payout " + alert.status() + " for " + alert.companyName());
        message.setText("""
                A LandVault payout needs attention.

                    Company:   %s
                    Payout:    %s (%s)
                    Amount:    NGN %s
                    Status:    %s

                %s

                Open the payouts screen to review it and, if needed, pay that part again.
                """.formatted(alert.companyName(), alert.payoutReference(), alert.part(), alert.amount(),
                alert.status(), alert.detail()));
        mailSender.send(message);
        log.info("Payout problem alert delivered to a Super Admin for {}", alert.companyName());
    }

    @Override
    public void refundNeedsAccount(RefundNeedsAccountAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Your LandVault refund needs your bank account");
        message.setText("""
                Hello,

                We're refunding NGN %s for your payment %s. Because it was paid by bank transfer, the payment \
                provider needs to know which account to send it to.

                Sign in to LandVault and add a bank account in your own name to receive it.
                """.formatted(alert.amount(), alert.paymentReference()));
        mailSender.send(message);
        log.info("Refund account request delivered to a buyer");
    }

    @Override
    public void refundProblem(RefundProblemAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Refund " + alert.status() + " for payment " + alert.paymentReference());
        message.setText("""
                A LandVault refund needs attention.

                    Payment:   %s
                    Amount:    NGN %s
                    Status:    %s

                %s
                """.formatted(alert.paymentReference(), alert.amount(), alert.status(), alert.detail()));
        mailSender.send(message);
        log.info("Refund problem alert delivered to a Super Admin");
    }

    @Override
    public void latePaymentResolved(LatePaymentAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        if (alert.allocated()) {
            message.setSubject("Good news: " + alert.plotLabel() + " at " + alert.estateName() + " is yours");
            message.setText("""
                    Hello,

                    Your payment of NGN %s arrived after your reservation had ended. The plot was still \
                    available, so it has been allocated to you: %s, %s.

                    You'll find it in your LandVault account.
                    """.formatted(alert.amount(), alert.plotLabel(), alert.estateName()));
        } else {
            message.setSubject("Your payment for " + alert.plotLabel() + " is being returned");
            message.setText("""
                    Hello,

                    Your payment of NGN %s arrived after your reservation for %s, %s had ended, and it \
                    couldn't be allocated: %s

                    The full amount is being returned to you. You'll see the refund in your LandVault account.
                    """.formatted(alert.amount(), alert.plotLabel(), alert.estateName(), alert.reason()));
        }
        mailSender.send(message);
        log.info("Late-payment outcome delivered to a buyer");
    }
}
